/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.awssdk.services.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import software.amazon.awssdk.NonRetryableException;
import software.amazon.awssdk.RetryableException;
import software.amazon.awssdk.SdkClientException;
import software.amazon.awssdk.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.exception.ClientExecutionTimeoutException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.sync.RequestBody;
import software.amazon.awssdk.sync.StreamingResponseHandler;

public class GetObjectFaultIntegrationTest extends S3IntegrationTestBase {

    private static final String BUCKET = getBucketName(GetObjectFaultIntegrationTest.class);

    private static final String KEY = "some-key";

    private static S3Client s3ClientWithTimeout;

    @BeforeClass
    public static void setupFixture() {
        createBucket(BUCKET);
        s3.putObject(PutObjectRequest.builder()
                                     .bucket(BUCKET)
                                     .key(KEY)
                                     .build(), RequestBody.of("some contents"));
        s3ClientWithTimeout = s3ClientBuilder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                                                                  .totalExecutionTimeout(Duration.ofSeconds(5))
                                                                  .build())
                .build();
    }

    @AfterClass
    public static void tearDownFixture() {
        deleteBucketAndAllContents(BUCKET);
    }

    @Test
    public void handlerThrowsRetryableException_RetriedUpToLimit() throws Exception {
        RequestCountingResponseHandler<GetObjectResponse, ?> handler = new RequestCountingResponseHandler<>(
                (resp, in) -> {
                    throw new RetryableException("");
                });
        assertThatThrownBy(() -> s3.getObject(getObjectRequest(), handler))
                .isInstanceOf(SdkClientException.class);
        assertThat(handler.currentCallCount()).isEqualTo(4);
    }

    @Test
    public void handlerThrowsNonRetryableException_RequestNotRetried() throws Exception {
        RequestCountingResponseHandler<GetObjectResponse, ?> handler = new RequestCountingResponseHandler<>(
                (resp, in) -> {
                    throw new NonRetryableException("");
                });
        assertThatThrownBy(() -> s3.getObject(getObjectRequest(), handler))
                .isInstanceOf(SdkClientException.class);
        assertThat(handler.currentCallCount()).isEqualTo(1);
    }

    @Test
    public void slowHandlerIsInterrupted() throws Exception {
        RequestCountingResponseHandler<GetObjectResponse, ?> handler = new RequestCountingResponseHandler<>(
                (resp, in) -> {
                    try {
                        Thread.sleep(10_000);
                        fail("Expected Interrupted Exception");
                    } catch (InterruptedException ie) {
                        throw ie;
                    }
                    return null;
                });
        assertThatThrownBy(() -> s3ClientWithTimeout.getObject(getObjectRequest(), handler))
                .isInstanceOf(ClientExecutionTimeoutException.class);
        assertThat(handler.currentCallCount()).isEqualTo(1);
    }

    /**
     * Customers should be able to just re-interrupt the current thread instead of having to throw {@link InterruptedException}.
     */
    @Test
    public void slowHandlerIsInterrupted_SetsInterruptFlag() throws Exception {
        RequestCountingResponseHandler<GetObjectResponse, ?> handler = new RequestCountingResponseHandler<>(
                (resp, in) -> {
                    try {
                        Thread.sleep(10_000);
                        fail("Expected Interrupted Exception");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted");
                    }
                    return null;
                });
        assertThatThrownBy(() -> s3ClientWithTimeout.getObject(getObjectRequest(), handler))
                .isInstanceOf(ClientExecutionTimeoutException.class);
        assertThat(handler.currentCallCount()).isEqualTo(1);
    }

    /**
     * If a response handler does not preserve the interrupt status or throw an {@link InterruptedException} then
     * we can't translate the exception to a {@link ClientExecutionTimeoutException}.
     */
    @Test
    public void handlerSquashsInterrupt_DoesNotThrowClientTimeoutException() throws Exception {
        RequestCountingResponseHandler<GetObjectRequest, ?> handler = new RequestCountingResponseHandler<>(
                (resp, in) -> {
                    try {
                        Thread.sleep(10_000);
                        fail("Expected Interrupted Exception");
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }
                    return null;
                });
        assertThatThrownBy(() -> s3ClientWithTimeout.getObject(getObjectRequest(), handler))
                .isNotInstanceOf(ClientExecutionTimeoutException.class);
        assertThat(handler.currentCallCount()).isEqualTo(1);
    }

    private GetObjectRequest getObjectRequest() {
        return GetObjectRequest.builder()
                               .bucket(BUCKET)
                               .key(KEY)
                               .build();
    }

    /**
     * Wrapper around a {@link StreamingResponseHandler} that counts how many times it's been invoked.
     */
    private static class RequestCountingResponseHandler<ResponseT, ReturnT>
            implements StreamingResponseHandler<ResponseT, ReturnT> {

        private final StreamingResponseHandler<ResponseT, ReturnT> delegate;
        private final AtomicInteger callCount = new AtomicInteger(0);

        private RequestCountingResponseHandler(StreamingResponseHandler<ResponseT, ReturnT> delegate) {
            this.delegate = delegate;
        }

        @Override
        public ReturnT apply(ResponseT response, AbortableInputStream inputStream) throws Exception {
            callCount.incrementAndGet();
            return delegate.apply(response, inputStream);
        }

        @Override
        public boolean needsConnectionLeftOpen() {
            return delegate.needsConnectionLeftOpen();
        }

        public int currentCallCount() {
            return callCount.get();
        }
    }
}
