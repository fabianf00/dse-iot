package at.ac.univie.dse.iot.msml.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.ac.univie.dse.iot.msml.storage.PageRequest;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FilterParserTest {

    @Test
    void noParametersMeansEverythingOfTheKindAndTheFirstPage() {
        assertEquals(ResultFilter.all(ResultKind.ANOMALY), FilterParser.filterForList(ResultKind.ANOMALY, Map.of()));
        assertEquals(PageRequest.firstPage(), FilterParser.page(Map.of()));
    }

    @Test
    void allFiltersAreParsed() {
        ResultFilter filter = FilterParser.filterForList(ResultKind.ANOMALY, Map.of(
                "dataType", List.of("temperature", "CO2"),
                "chainId", List.of("temp-sudden-drop"),
                "from", List.of("2021-11-20T14:00:00Z"),
                "to", List.of("2021-11-20T15:00:00Z"),
                "commented", List.of("true")));

        assertEquals(new ResultFilter(ResultKind.ANOMALY, Set.of("temperature", "co2"), "temp-sudden-drop",
                Instant.parse("2021-11-20T14:00:00Z"), Instant.parse("2021-11-20T15:00:00Z"), true, null, null), filter);
    }

    @Test
    void pagingIsParsed() {
        PageRequest page = FilterParser.page(Map.of("limit", List.of("10"), "offset", List.of("20"),
                "sort", List.of("occurredAt,asc")));

        assertEquals(new PageRequest(10, 20, PageRequest.SortOrder.OLDEST_FIRST), page);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "limit     | 0                     | limit must be between 1 and 500",
            "limit     | 501                   | limit must be between 1 and 500",
            "limit     | many                  | limit must be a number",
            "offset    | -1                    | offset must be >= 0",
            "sort      | occurredAt            | sort must be occurredAt,desc or occurredAt,asc",
            "from      | yesterday             | from must be an ISO-8601 instant such as 2021-11-20T14:00:00Z",
            "commented | yes                   | commented must be true or false",
            "dataType  | air pressure          | dataType: invalid data type 'air pressure': expected a name matching [a-z][a-z0-9_-]*",
            "colour    | red                   | unknown parameter colour"})
    void invalidValuesAreRejectedWithTheParameterName(String name, String value, String message) {
        ApiException e = assertThrows(ApiException.class, () -> {
            Map<String, List<String>> params = Map.of(name, List.of(value));
            FilterParser.filterForList(ResultKind.ANOMALY, params);
            FilterParser.page(params);
        });

        assertEquals(400, e.status());
        assertEquals(message, e.getMessage());
    }

    @Test
    void predictionsHaveNoChain() {
        ApiException e = assertThrows(ApiException.class, () -> FilterParser.filterForList(ResultKind.PREDICTION,
                Map.of("chainId", List.of("x"))));

        assertEquals("unknown parameter chainId (only anomalies have a chain)", e.getMessage());
    }

    @Test
    void bulkDeleteTakesNoPagingParameters() {
        ApiException e = assertThrows(ApiException.class, () -> FilterParser.filterForDelete(ResultKind.ANOMALY,
                Map.of("dataType", List.of("co2"), "limit", List.of("5"))));

        assertEquals("unknown parameter limit", e.getMessage());
    }

    @Test
    void contradictoryOrRepeatedValuesAreRejected() {
        assertEquals("from must not be after to", assertThrows(ApiException.class, () -> FilterParser.filterForList(
                ResultKind.ANOMALY, Map.of("from", List.of("2021-11-20T15:00:00Z"), "to", List.of("2021-11-20T14:00:00Z"))))
                .getMessage());
        assertEquals("chainId may only be given once", assertThrows(ApiException.class, () -> FilterParser.filterForList(
                ResultKind.ANOMALY, Map.of("chainId", List.of("a", "b")))).getMessage());
        assertEquals("commented must not be empty", assertThrows(ApiException.class, () -> FilterParser.filterForList(
                ResultKind.ANOMALY, Map.of("commented", List.of(" ")))).getMessage());
    }

    @Test
    void missingOptionalValuesStayNull() {
        ResultFilter filter = FilterParser.filterForDelete(ResultKind.PREDICTION, Map.of("dataType", List.of("co2")));

        assertNull(filter.from());
        assertNull(filter.commented());
        assertEquals(Set.of("co2"), filter.dataTypes());
    }
}
