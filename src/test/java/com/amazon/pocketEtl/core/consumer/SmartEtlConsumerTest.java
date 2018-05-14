/*
 * Copyright 2017-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.amazon.pocketEtl.core.consumer;

import com.amazon.pocketEtl.EtlTestBase;
import com.amazon.pocketEtl.core.EtlStreamObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class SmartEtlConsumerTest extends EtlTestBase {
    private final static String TEST_NAME = "TestName";

    @Mock
    private EtlConsumer mockWrappedEtlConsumer;

    @Mock
    private EtlStreamObject mockEtlStreamObject;

    private SmartEtlConsumer smartConsumer;

    @Before
    public void constructSmartTrackingConsumer() {
        smartConsumer = new SmartEtlConsumer(TEST_NAME, mockWrappedEtlConsumer);
    }

    @Test
    public void openOpensWrappedConsumer() {
        smartConsumer.open(etlProfilingScope.getMetrics());

        verify(mockWrappedEtlConsumer).open(eq(etlProfilingScope.getMetrics()));
    }

    @Test
    public void openOpensWrappedConsumerOnlyOnceWhenCalledMultipleTimes() {
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.open(etlProfilingScope.getMetrics());

        verify(mockWrappedEtlConsumer, times(1)).open(eq(etlProfilingScope.getMetrics()));
    }

    @Test
    public void closeClosesWrappedConsumer() throws Exception {
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.close();

        verify(mockWrappedEtlConsumer).close();
    }

    @Test
    public void consumePassesObjectToWrappedConsumer() {
        smartConsumer.open(mockMetrics);
        smartConsumer.consume(mockEtlStreamObject);

        verify(mockWrappedEtlConsumer).consume(eq(mockEtlStreamObject));
    }

    @Test
    public void closeOnlyClosesWhenOpenCountHasBeenReached() throws Exception {
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.close();
        smartConsumer.close();
        verify(mockWrappedEtlConsumer, never()).close();

        smartConsumer.close();
        verify(mockWrappedEtlConsumer, times(1)).close();
    }

    @Test(expected = IllegalStateException.class)
    public void attemptingToCloseTooManyTimesThrowsIllegalStateException() throws Exception {
        smartConsumer.open(etlProfilingScope.getMetrics());
        smartConsumer.close();
        smartConsumer.close();
    }
}
