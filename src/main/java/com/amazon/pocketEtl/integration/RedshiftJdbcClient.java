/*
 * Copyright 2017-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.amazon.pocketEtl.integration;

import com.amazon.pocketEtl.EtlMetrics;
import com.amazon.pocketEtl.EtlProfilingScope;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.logging.log4j.LogManager.getLogger;

/**
 * JDBC wrapper with Redshift-specific functionality and helper functions.
 */
public class RedshiftJdbcClient {
    private final static Logger logger = getLogger(RedshiftJdbcClient.class);

    private static final String STAGE_TABLE_NAME_TOKEN = "$stage_table_name";
    private static final String DESTINATION_TABLE_NAME_TOKEN = "$destination_table_name";
    private static final String COLUMN_LIST_TOKEN = "$column_list";
    private static final String COLUMN_MATCH_TOKEN = "$column_match";
    private static final String S3_SOURCE_URL_TOKEN = "$s3_source_url";
    private static final String IAM_ROLE_TOKEN = "$iam_role";
    private static final String AWS_S3_REGION_TOKEN = "$aws_s3_region";

    private static final String DROP_TABLE_IF_EXISTS_SQL =
            "drop table if exists " + STAGE_TABLE_NAME_TOKEN;

    private static final String DROP_TABLE_SQL =
            "drop table " + STAGE_TABLE_NAME_TOKEN;

    private static final String CREATE_TEMPORARY_TABLE_LIKE_SQL =
            "create temp table " + STAGE_TABLE_NAME_TOKEN +
                    " (like " + DESTINATION_TABLE_NAME_TOKEN + " including defaults)";

    private static final String COPY_SQL =
            "copy " + STAGE_TABLE_NAME_TOKEN + "(" + COLUMN_LIST_TOKEN + ") " +
                    "from '" + S3_SOURCE_URL_TOKEN + "' " +
                    "iam_role '" + IAM_ROLE_TOKEN + "' " +
                    "region '" + AWS_S3_REGION_TOKEN + "' " +
                    "removequotes";

    private static final String DELETE_FROM_TABLE_USING_TEMPORARY_TABLE =
            "delete from " + DESTINATION_TABLE_NAME_TOKEN + " " +
                    "using " + STAGE_TABLE_NAME_TOKEN + " " +
                    "where " + COLUMN_MATCH_TOKEN;

    private static final String INSERT_FROM_TEMPORARY_TABLE =
            "insert into " + DESTINATION_TABLE_NAME_TOKEN + " " +
                    "select * from " + STAGE_TABLE_NAME_TOKEN;

    private final DataSource dataSource;

    /**
     * Standard constructor.
     *
     * @param dataSource JDBC datasource for a Redshift database.
     */
    public RedshiftJdbcClient(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Loads data from an S3 file (PSV) into a staging table, then deletes and inserts all the records into a destination
     * table.
     *
     * @param fileColumnNames      List of column names to map the data onto (in the order they are found in the file)
     * @param keyColumnNames       List of key column names to uniquely identify records from this dataset
     * @param destinationTableName The table name of the final destination table
     * @param sourceS3bucket       S3 Bucket where the data to loader can be found
     * @param sourceS3prefix       Key prefix to locate al the files in S3 to loader
     * @param sourceS3region       S3 region where the bucket is hosted
     * @param iamRoleToAssume      IAM role assumed by Redshift to read the data from S3
     * @param parentMetrics        Parent metrics object to log timers and counters into
     */
    public void copyAndMerge(List<String> fileColumnNames, List<String> keyColumnNames, String destinationTableName, String sourceS3bucket,
                             String sourceS3prefix, String sourceS3region, String iamRoleToAssume, EtlMetrics parentMetrics) {
        try (EtlProfilingScope ignored = new EtlProfilingScope(parentMetrics, "RedshiftJdbcClient.copyAndMerge")) {
            String s3Url = String.format("s3://%s/%s/", sourceS3bucket, sourceS3prefix);
            String stageTableName = generateStageTableName();
            Connection connection = null;

            try {
                connection = dataSource.getConnection();

                if (connection == null) {
                    throw new RuntimeException("DataSource returned null connection");
                }

                dropTableIfExists(connection, stageTableName);
                createTemporaryTableLikeExistingTable(connection, stageTableName, destinationTableName);

                copyFromS3ToTemporaryTable(connection, fileColumnNames, stageTableName, s3Url, iamRoleToAssume, sourceS3region);

                connection.setAutoCommit(false);

                deleteFromRealTableUsingTemporaryTable(connection, keyColumnNames, stageTableName, destinationTableName);
                insertFromTemporaryTable(connection, stageTableName, destinationTableName);

                connection.commit();

                connection.setAutoCommit(true);
                dropTable(connection, stageTableName);
                connection.close();
            } catch (SQLException e) {
                if (connection != null) {
                    // Attempt a rollback if something went wrong
                    logger.warn("SQL exception thrown during Redshift copyAndMerge operation, rolling back transaction", e);

                    try {
                        connection.rollback();
                    } catch (SQLException nestedException) {
                        logger.warn("SQL exception thrown during rollback", nestedException);
                    }
                }
            } finally {
                // If the connection is still open then attempt to drop the staging table if it exists then close it
                try {
                    if (connection != null && !connection.isClosed()) {
                        dropTableIfExists(connection, stageTableName);
                        connection.close();
                    }
                } catch (SQLException ignored2) {
                }
            }
        }
    }

    private String generateStageTableName() {
        return String.format("stage_%d", DateTime.now().getMillis());
    }

    private String performSqlTableSubstitutions(String sql, String stageTableName, String destinationTableName) {
        return sql.replace(STAGE_TABLE_NAME_TOKEN, stageTableName)
                .replace(DESTINATION_TABLE_NAME_TOKEN, destinationTableName);
    }

    private void dropTableIfExists(Connection connection, String stageTableName) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlTableSubstitutions(DROP_TABLE_IF_EXISTS_SQL, stageTableName, ""))) {
            preparedStatement.execute();
        }
    }

    private void dropTable(Connection connection, String stageTableName) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlTableSubstitutions(DROP_TABLE_SQL, stageTableName, ""))) {
            preparedStatement.execute();
        }
    }

    private void createTemporaryTableLikeExistingTable(Connection connection, String temporaryTableName,
                                                       String likeTableName) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlTableSubstitutions(CREATE_TEMPORARY_TABLE_LIKE_SQL, temporaryTableName, likeTableName))) {
            preparedStatement.execute();
        }
    }

    private String performSqlCopySubstitutions(String sql, String temporaryTableName, String combinedColumnNames,
                                               String s3SourceUrl, String iamRole, String awsS3Region) {
        return sql.replace(STAGE_TABLE_NAME_TOKEN, temporaryTableName)
                .replace(COLUMN_LIST_TOKEN, combinedColumnNames)
                .replace(S3_SOURCE_URL_TOKEN, s3SourceUrl)
                .replace(IAM_ROLE_TOKEN, iamRole)
                .replace(AWS_S3_REGION_TOKEN, awsS3Region);
    }

    private void copyFromS3ToTemporaryTable(Connection connection, List<String> fileColumnNames, String temporaryTableName,
                                            String s3SourceUrl, String iamRole, String awsS3Region) throws SQLException {

        String combinedColumnNames = fileColumnNames.stream().collect(Collectors.joining(","));
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlCopySubstitutions(COPY_SQL, temporaryTableName, combinedColumnNames, s3SourceUrl,
                        iamRole, awsS3Region))) {
            preparedStatement.execute();
        }
    }

    private String performSqlDeleteFromTableSubstitutions(String sql, String columnMatchSQL, String temporaryTableName,
                                                          String destinationTableName) {
        return performSqlTableSubstitutions(sql, temporaryTableName, destinationTableName)
                .replace(COLUMN_MATCH_TOKEN, columnMatchSQL);
    }

    private void deleteFromRealTableUsingTemporaryTable(Connection connection, List<String> keyColumnNames,
                                                        String temporaryTableName, String destinationTableName)
            throws SQLException {
        String columnMatchSQL = keyColumnNames.stream()
                .map(columnName -> String.format("%s.%s = %s.%s", destinationTableName, columnName, temporaryTableName,
                        columnName))
                .collect(Collectors.joining(" and "));
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlDeleteFromTableSubstitutions(DELETE_FROM_TABLE_USING_TEMPORARY_TABLE, columnMatchSQL,
                        temporaryTableName, destinationTableName))) {
            preparedStatement.execute();
        }
    }

    private void insertFromTemporaryTable(Connection connection, String temporaryTableName, String destinationTableName)
            throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                performSqlTableSubstitutions(INSERT_FROM_TEMPORARY_TABLE, temporaryTableName, destinationTableName))) {
            preparedStatement.execute();
        }
    }
}
