/*
 * Copyright 2017-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.amazon.pocketEtl.core;

import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class EtlStreamObjectTest {
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO1 {
        private String first;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO2 {
        private String second;
        private String first;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO3 {
        private int first;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO4 {
        private DateTime first;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO5 {
        TestDTO1 outer;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO6 {
        TestDTO2 outer;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    private static class TestDTO7 {
        Map<String, String> outer;
    }

    @Test
    public void settingAndThenGettingTheSameDTOProducesTwoEqualObjects() {
        EtlStreamObject etlStreamObject = new EtlStreamObject();
        TestDTO1 expectedDTO = TestDTO1.builder()
                .first("first-string")
                .build();

        etlStreamObject.set(expectedDTO);
        TestDTO1 actualDTO = etlStreamObject.get(TestDTO1.class);

        assertThat(actualDTO, equalTo(expectedDTO));
    }

    @Test
    public void valuesNotCurrentlyInViewAreTunneled() {
        EtlStreamObject etlStreamObject = new EtlStreamObject();
        TestDTO2 initialDto = TestDTO2.builder()
                .first("first-string")
                .second("second-string")
                .build();

        etlStreamObject.set(initialDto);
        TestDTO1 newDTO = etlStreamObject.get(TestDTO1.class);
        newDTO.setFirst("updated-first-string");
        etlStreamObject.set(newDTO);

        TestDTO2 finalDTO = etlStreamObject.get(TestDTO2.class);

        assertThat(finalDTO.getFirst(), equalTo("updated-first-string"));
        assertThat(finalDTO.getSecond(), equalTo("second-string"));
    }

    @Test
    public void valueWrittenAsAnIntegerCanBeLaterReadAsAString() {
        EtlStreamObject etlStreamObject = new EtlStreamObject();
        TestDTO3 initialDTO = TestDTO3.builder()
                .first(123)
                .build();

        etlStreamObject.set(initialDTO);

        TestDTO1 finalDTO = etlStreamObject.get(TestDTO1.class);

        assertThat(finalDTO.getFirst(), equalTo("123"));
    }

    @Test
    public void valueWrittenAsAStringCanBeLaterReadAsAnInteger() {
        EtlStreamObject etlStreamObject = new EtlStreamObject();
        TestDTO1 initialDTO = TestDTO1.builder()
                .first("123")
                .build();

        etlStreamObject.set(initialDTO);

        TestDTO3 finalDTO = etlStreamObject.get(TestDTO3.class);

        assertThat(finalDTO.getFirst(), equalTo(123));
    }

    @Test
    public void canSetAndGetDateTime() {
        DateTime dateTime = DateTime.now();
        EtlStreamObject etlStreamObject = new EtlStreamObject().with(TestDTO4.builder().first(dateTime).build());

        TestDTO4 result = etlStreamObject.get(TestDTO4.class);

        assertThat(result.getFirst().withZone(dateTime.getZone()), equalTo(dateTime));
    }

    @Test
    public void canSerializeAndDeserializeDeepDataStructures() {
        TestDTO5 objectToSerialize = TestDTO5.builder().outer(TestDTO1.builder().first("test").build()).build();

        EtlStreamObject etlStreamObject = new EtlStreamObject().with(objectToSerialize);
        TestDTO5 result = etlStreamObject.get(TestDTO5.class);

        assertThat(result, equalTo(objectToSerialize));
    }

    @Test
    public void canTunnelDeepDataStructures() {
        TestDTO6 objectToSerialize =
                TestDTO6.builder().outer(TestDTO2.builder().first("test-1").second("test-2").build()).build();

        EtlStreamObject etlStreamObject = new EtlStreamObject().with(objectToSerialize);
        TestDTO5 intermediateObject = etlStreamObject.get(TestDTO5.class);
        intermediateObject.getOuter().setFirst("test-3");
        etlStreamObject.set(intermediateObject);

        TestDTO6 expectedObject =
                TestDTO6.builder().outer(TestDTO2.builder().first("test-3").second("test-2").build()).build();
        TestDTO6 actualObject = etlStreamObject.get(TestDTO6.class);

        assertThat(actualObject, equalTo(expectedObject));
    }

    @Test
    public void handlesMapsKeyedByString() {
        TestDTO7 objectToSerialize = TestDTO7.builder().outer(ImmutableMap.of("key1", "value1")).build();

        EtlStreamObject etlStreamObject = new EtlStreamObject().with(objectToSerialize);
        TestDTO7 intermediateObject = etlStreamObject.get(TestDTO7.class);

        assertThat(intermediateObject.getOuter().get("key1"), equalTo("value1"));
        etlStreamObject.set(intermediateObject);

        TestDTO7 result = etlStreamObject.get(TestDTO7.class);
        assertThat(result, equalTo(objectToSerialize));
    }

    // The default implementation of LoggingStrategy requires EtlStreamObject.get not to throw when converting to
    // Object.class
    @Test
    public void getAsGenericObjectDoesNotThrowException() {
        TestDTO1 objectToSerialize = TestDTO1.builder().first("test").build();
        EtlStreamObject etlStreamObject = new EtlStreamObject().with(objectToSerialize);

        etlStreamObject.get(Object.class);
    }
}
