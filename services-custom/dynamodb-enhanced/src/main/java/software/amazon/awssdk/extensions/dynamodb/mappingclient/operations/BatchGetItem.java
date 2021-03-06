/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.extensions.dynamodb.mappingclient.operations;

import static java.util.Collections.emptyList;
import static software.amazon.awssdk.extensions.dynamodb.mappingclient.core.Utils.readAndTransformSingleItem;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.DatabaseOperation;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.MappedTable;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.MapperExtension;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.OperationContext;
import software.amazon.awssdk.extensions.dynamodb.mappingclient.core.TransformIterable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.paginators.BatchGetItemIterable;

@SdkPublicApi
public class BatchGetItem
    implements DatabaseOperation<BatchGetItemRequest,
                                 BatchGetItemIterable,
                                 Iterable<BatchGetItem.ResultsPage>> {
    private final Collection<ReadBatch> readBatches;

    private BatchGetItem(Collection<ReadBatch> readBatches) {
        this.readBatches = readBatches;
    }

    public static BatchGetItem of(Collection<ReadBatch> readBatches) {
        return new BatchGetItem(readBatches);
    }

    public static BatchGetItem of(ReadBatch... readBatches) {
        return new BatchGetItem(Arrays.asList(readBatches));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().readBatches(readBatches);
    }

    @Override
    public BatchGetItemRequest generateRequest(MapperExtension mapperExtension) {
        Map<String, KeysAndAttributes> requestItems = new HashMap<>();
        readBatches.forEach(readBatch -> readBatch.addReadRequestsToMap(requestItems));

        return BatchGetItemRequest.builder()
                                  .requestItems(Collections.unmodifiableMap(requestItems))
                                  .build();
    }

    @Override
    public Iterable<ResultsPage> transformResponse(BatchGetItemIterable response,
                                                   MapperExtension mapperExtension) {
        return TransformIterable.of(response,
            batchGetItemResponse -> new ResultsPage(batchGetItemResponse, mapperExtension));
    }

    @Override
    public Function<BatchGetItemRequest, BatchGetItemIterable> getServiceCall(DynamoDbClient dynamoDbClient) {
        return dynamoDbClient::batchGetItemPaginator;
    }

    public Collection<ReadBatch> getReadBatches() {
        return readBatches;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BatchGetItem that = (BatchGetItem) o;

        return readBatches != null ? readBatches.equals(that.readBatches) : that.readBatches == null;
    }

    @Override
    public int hashCode() {
        return readBatches != null ? readBatches.hashCode() : 0;
    }

    public static final class Builder {
        private Collection<ReadBatch> readBatches;

        private Builder() {
        }

        public Builder readBatches(Collection<ReadBatch> readBatches) {
            this.readBatches = readBatches;
            return this;
        }

        public BatchGetItem build() {
            return new BatchGetItem(this.readBatches);
        }
    }

    public static class ResultsPage {
        private final BatchGetItemResponse batchGetItemResponse;
        private final MapperExtension mapperExtension;

        private ResultsPage(BatchGetItemResponse batchGetItemResponse, MapperExtension mapperExtension) {
            this.batchGetItemResponse = batchGetItemResponse;
            this.mapperExtension = mapperExtension;
        }

        public <T> List<T> getResultsForTable(MappedTable<T> mappedTable) {
            List<Map<String, AttributeValue>> results =
                batchGetItemResponse.responses()
                                    .getOrDefault(mappedTable.getTableName(), emptyList());

            return results.stream()
                          .map(itemMap -> readAndTransformSingleItem(itemMap,
                                                                     mappedTable.getTableSchema(),
                                                                     OperationContext.of(mappedTable.getTableName()),
                                                                     mapperExtension))
                          .collect(Collectors.toList());
        }
    }
}
