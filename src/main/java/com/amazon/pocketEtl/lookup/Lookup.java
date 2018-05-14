/*
 * Copyright 2017-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.amazon.pocketEtl.lookup;

import java.util.Optional;

/**
 * Interface for an object that provides lookup capability across a data-set. Used for random access querying or
 * filtering of data in the ETL. Specific implementations may be backed by data structures or wrap service queries.
 * @param <KeyType> The class type for keys in this data set.
 * @param <ValueType> The class type for values in this data set.
 */
public interface Lookup<KeyType, ValueType> {
    /**
     * Attempt to get a specific value from the data set for a given key.
     * @param key The key to search the data-set for.
     * @return The value stored for the given key, or empty if there was no matching value.
     */
    Optional<ValueType> get(KeyType key);
}
