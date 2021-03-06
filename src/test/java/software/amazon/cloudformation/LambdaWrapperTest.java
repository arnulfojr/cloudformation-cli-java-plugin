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
package software.amazon.cloudformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.cloudformation.model.OperationStatusCheckFailedException;
import software.amazon.cloudformation.exceptions.ResourceAlreadyExistsException;
import software.amazon.cloudformation.exceptions.ResourceNotFoundException;
import software.amazon.cloudformation.exceptions.TerminalException;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.proxy.CallbackAdapter;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.HandlerRequest;
import software.amazon.cloudformation.proxy.HandlerResponse;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.RequestData;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;
import software.amazon.cloudformation.resource.exceptions.ValidationException;
import software.amazon.cloudformation.scheduler.CloudWatchScheduler;

@ExtendWith(MockitoExtension.class)
public class LambdaWrapperTest {

    private static final String TEST_DATA_BASE_PATH = "src/test/java/software/amazon/cloudformation/data/%s";

    @Mock
    private CallbackAdapter<TestModel> callbackAdapter;

    @Mock
    private CredentialsProvider platformCredentialsProvider;

    @Mock
    private CredentialsProvider providerLoggingCredentialsProvider;

    @Mock
    private MetricsPublisher platformMetricsPublisher;

    @Mock
    private MetricsPublisher providerMetricsPublisher;

    @Mock
    private CloudWatchLogPublisher providerEventsLogger;

    @Mock
    private LogPublisher platformEventsLogger;

    @Mock
    private CloudWatchScheduler scheduler;

    @Mock
    private SchemaValidator validator;

    @Mock
    private LambdaLogger lambdaLogger;

    @Mock
    private ResourceHandlerRequest<TestModel> resourceHandlerRequest;

    @Mock
    private SdkHttpClient httpClient;

    private WrapperOverride wrapper;

    @BeforeEach
    public void initWrapper() {
        wrapper = new WrapperOverride(callbackAdapter, platformCredentialsProvider, providerLoggingCredentialsProvider,
                                      platformEventsLogger, providerEventsLogger, platformMetricsPublisher,
                                      providerMetricsPublisher, scheduler, validator, httpClient);
    }

    public static InputStream loadRequestStream(final String fileName) {
        final File file = new File(String.format(TEST_DATA_BASE_PATH, fileName));

        try {
            return new FileInputStream(file);
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Context getLambdaContext() {
        final Context context = mock(Context.class);
        lenient().when(context.getInvokedFunctionArn()).thenReturn("arn:aws:lambda:aws-region:acct-id:function:testHandler:PROD");
        lenient().when(context.getLogger()).thenReturn(lambdaLogger);
        return context;
    }

    private void verifyInitialiseRuntime() {
        verify(platformCredentialsProvider).setCredentials(any(Credentials.class));
        verify(providerLoggingCredentialsProvider).setCredentials(any(Credentials.class));
        verify(callbackAdapter).refreshClient();
        verify(platformMetricsPublisher).refreshClient();
        verify(providerMetricsPublisher).refreshClient();
        verify(scheduler).refreshClient();
    }

    private void verifyHandlerResponse(final OutputStream out, final HandlerResponse<TestModel> expected) throws IOException {
        final Serializer serializer = new Serializer();
        final HandlerResponse<
            TestModel> handlerResponse = serializer.deserialize(out.toString(), new TypeReference<HandlerResponse<TestModel>>() {
            });

        assertThat(handlerResponse.getBearerToken()).isEqualTo(expected.getBearerToken());
        assertThat(handlerResponse.getErrorCode()).isEqualTo(expected.getErrorCode());
        assertThat(handlerResponse.getNextToken()).isEqualTo(expected.getNextToken());
        assertThat(handlerResponse.getOperationStatus()).isEqualTo(expected.getOperationStatus());
        assertThat(handlerResponse.getStabilizationData()).isEqualTo(expected.getStabilizationData());
        assertThat(handlerResponse.getResourceModel()).isEqualTo(expected.getResourceModel());
    }

    @ParameterizedTest
    @CsvSource({ "create.request.json,CREATE", "update.request.json,UPDATE", "delete.request.json,DELETE",
        "read.request.json,READ", "list.request.json,LIST" })
    public void invokeHandler_nullResponse_returnsFailure(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);
        final TestModel model = new TestModel();

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // validation failure metric should be published for final error handling
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(TerminalException.class), any(HandlerErrorCode.class));
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // all metrics should be published even on terminal failure
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            verify(providerEventsLogger).refreshClient();
            verify(providerEventsLogger, times(2)).publishLogEvent(any());
            verifyNoMoreInteractions(providerEventsLogger);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED).message("Handler failed to provide a response.").build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(resourceHandlerRequest);
            assertThat(wrapper.action).isEqualTo(action);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @Test
    public void invokeHandlerForCreate_without_customer_loggingCredentials() throws IOException {
        invokeHandler_without_customerLoggingCredentials("create.request-without-logging-credentials.json", Action.CREATE);
    }

    private void invokeHandler_without_customerLoggingCredentials(final String requestDataPath, final Action action)
        throws IOException {
        final TestModel model = new TestModel();

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verify(platformCredentialsProvider).setCredentials(any(Credentials.class));
            verify(providerLoggingCredentialsProvider, times(0)).setCredentials(any(Credentials.class));
            verify(callbackAdapter).refreshClient();
            verify(platformMetricsPublisher).refreshClient();
            verify(providerMetricsPublisher, times(0)).refreshClient();
            verify(scheduler).refreshClient();

            // validation failure metric should be published for final error handling
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(TerminalException.class), any(HandlerErrorCode.class));
            verify(providerMetricsPublisher, times(0)).publishExceptionMetric(any(Instant.class), any(),
                any(TerminalException.class), any(HandlerErrorCode.class));

            // all metrics should be published even on terminal failure
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());
            verify(providerMetricsPublisher, times(0)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(providerMetricsPublisher, times(0)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            verifyNoMoreInteractions(providerEventsLogger);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED).message("Handler failed to provide a response.").build());
        }
    }

    @ParameterizedTest
    @CsvSource({ "create.request.json,CREATE", "update.request.json,UPDATE", "delete.request.json,DELETE",
        "read.request.json,READ", "list.request.json,LIST" })
    public void invokeHandler_handlerFailed_returnsFailure(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.FAILED).errorCode(HandlerErrorCode.InternalFailure).message("Custom Fault").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED).message("Custom Fault").build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(resourceHandlerRequest);
            assertThat(wrapper.action).isEqualTo(action);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @Test
    public void invokeHandler_withNullInput() throws IOException {
        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.FAILED).errorCode(HandlerErrorCode.InternalFailure).message("Custom Fault").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = null; final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();
            wrapper.handleRequest(in, out, context);
            verifyNoMoreInteractions(callbackAdapter, platformMetricsPublisher, platformEventsLogger, providerMetricsPublisher,
                providerEventsLogger);
        }
    }

    @ParameterizedTest
    @CsvSource({ "create.request.json,CREATE", "update.request.json,UPDATE", "delete.request.json,DELETE",
        "read.request.json,READ", "list.request.json,LIST" })
    public void invokeHandler_CompleteSynchronously_returnsSuccess(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);
        final TestModel model = new TestModel();

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.SUCCESS).build());

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(resourceHandlerRequest);
            assertThat(wrapper.action).isEqualTo(action);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @Test
    public void invokeHandler_DependenciesInitialised_CompleteSynchronously_returnsSuccess() throws IOException {
        final Action action = Action.CREATE;
        final WrapperOverride wrapper = new WrapperOverride();
        final TestModel model = new TestModel();

        // if the handler responds Complete, this is treated as a successful synchronous
        // completion
        final ProgressEvent<TestModel,
            TestContext> pe = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);

        wrapper.setTransformResponse(resourceHandlerRequest);

        // use a request context in our payload to bypass certain callbacks
        try (final InputStream in = loadRequestStream("create.with-request-context.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // simply ensure all dependencies were setup correctly - behaviour is tested
            // through mocks
            assertThat(wrapper.serializer).isNotNull();
            assertThat(wrapper.loggerProxy).isNotNull();
            assertThat(wrapper.metricsPublisherProxy).isNotNull();
            assertThat(wrapper.lambdaLogger).isNotNull();
            assertThat(wrapper.platformCredentialsProvider).isNotNull();
            assertThat(wrapper.providerCredentialsProvider).isNotNull();
            assertThat(wrapper.cloudFormationProvider).isNotNull();
            assertThat(wrapper.platformCloudWatchProvider).isNotNull();
            assertThat(wrapper.providerCloudWatchProvider).isNotNull();
            assertThat(wrapper.platformCloudWatchEventsProvider).isNotNull();
            assertThat(wrapper.cloudWatchLogsProvider).isNotNull();
            assertThat(wrapper.validator).isNotNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "create.request.json,CREATE", "update.request.json,UPDATE", "delete.request.json,DELETE",
        "read.request.json,READ", "list.request.json,LIST" })
    public void invokeHandler_InProgress_returnsInProgress(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

                // re-invocation via CloudWatch should occur
                verify(scheduler, times(1)).rescheduleAfterMinutes(anyString(), eq(0),
                    ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

                // CloudFormation should receive a callback invocation
                verify(callbackAdapter, times(1)).reportProgress(eq("123456"), isNull(), eq(OperationStatus.IN_PROGRESS),
                    eq(OperationStatus.IN_PROGRESS), eq(TestModel.builder().property1("abc").property2(123).build()), isNull());

                // verify output response
                verifyHandlerResponse(out,
                    HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.IN_PROGRESS)
                        .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
            } else {
                verifyHandlerResponse(out,
                    HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.FAILED)
                        .errorCode(HandlerErrorCode.InternalFailure.name())
                        .message("READ and LIST handlers must return synchronously.").build());
                verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(action),
                    any(TerminalException.class), eq(HandlerErrorCode.InternalFailure));
                verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(action),
                    any(TerminalException.class), eq(HandlerErrorCode.InternalFailure));
            }
            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);
            verifyNoMoreInteractions(providerMetricsPublisher);
            verifyNoMoreInteractions(scheduler);

            // assert handler receives correct injections
            assertThat(wrapper.awsClientProxy).isNotNull();
            assertThat(wrapper.getRequest()).isEqualTo(resourceHandlerRequest);
            assertThat(wrapper.action).isEqualTo(action);
            assertThat(wrapper.callbackContext).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({ "create.with-request-context.request.json,CREATE", "update.with-request-context.request.json,UPDATE",
        "delete.with-request-context.request.json,DELETE" })
    public void reInvokeHandler_InProgress_returnsInProgress(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used, and any
        // interval >= 1 minute is scheduled
        // against CloudWatch. Shorter intervals are able to run locally within same
        // function context if runtime permits
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS).callbackDelaySeconds(60).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);
            verifyNoMoreInteractions(providerMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // re-invocation via CloudWatch should occur
            verify(scheduler, times(1)).rescheduleAfterMinutes(anyString(), eq(1),
                ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be"));

            // CloudFormation should receive a callback invocation
            verify(callbackAdapter, times(1)).reportProgress(eq("123456"), isNull(), eq(OperationStatus.IN_PROGRESS),
                eq(OperationStatus.IN_PROGRESS), eq(TestModel.builder().property1("abc").property2(123).build()), isNull());

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.IN_PROGRESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @ParameterizedTest
    @CsvSource({ "create.request.json,CREATE", "update.request.json,UPDATE", "delete.request.json,DELETE" })
    public void invokeHandler_SchemaValidationFailure(final String requestDataPath, final String actionAsString)
        throws IOException {
        final Action action = Action.valueOf(actionAsString);
        doThrow(ValidationException.class).when(validator).validateObject(any(JSONObject.class), any(JSONObject.class));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // validation failure metric should be published but no others
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(action),
                any(Exception.class), any(HandlerErrorCode.class));
            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(action),
                any(Exception.class), any(HandlerErrorCode.class));

            // all metrics should be published, even for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));
            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(action));

            // duration metric only published when the provider handler is invoked
            verifyNoMoreInteractions(platformMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // first report to acknowledge the task
            verify(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.IN_PROGRESS), eq(OperationStatus.PENDING),
                any(), any());

            // second report to record validation failure
            verify(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.FAILED), eq(OperationStatus.IN_PROGRESS),
                any(), any());
            verifyNoMoreInteractions(callbackAdapter);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InvalidRequest")
                .operationStatus(OperationStatus.FAILED).message("Model validation failed with unknown cause.").build());
        }
    }

    @Test
    public void invokeHandler_invalidModelTypes_causesSchemaValidationFailure() throws IOException {
        // use actual validator to verify behaviour
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, platformCredentialsProvider,
                                                            providerLoggingCredentialsProvider, platformEventsLogger,
                                                            providerEventsLogger, platformMetricsPublisher,
                                                            providerMetricsPublisher, scheduler, new Validator() {
                                                            }, httpClient);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.with-invalid-model-types.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InvalidRequest")
                    .operationStatus(OperationStatus.FAILED)
                    .message("Model validation failed (#/property1: expected type: String, found: JSONArray)").build());
        }
    }

    @Test
    public void invokeHandler_extraneousModelFields_causesSchemaValidationFailure() throws IOException {
        // use actual validator to verify behaviour
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, platformCredentialsProvider,
                                                            providerLoggingCredentialsProvider, platformEventsLogger,
                                                            providerEventsLogger, platformMetricsPublisher,
                                                            providerMetricsPublisher, scheduler, new Validator() {
                                                            }, httpClient);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.with-extraneous-model-fields.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // validation failure metric should be published but no others
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(Action.CREATE),
                any(Exception.class), any(HandlerErrorCode.class));

            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), eq(Action.CREATE),
                any(Exception.class), any(HandlerErrorCode.class));

            // all metrics should be published, even for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // first report to acknowledge the task
            verify(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.IN_PROGRESS), eq(OperationStatus.PENDING),
                any(), any());

            // second report to record validation failure
            verify(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.FAILED), eq(OperationStatus.IN_PROGRESS),
                any(), any());

            verifyNoMoreInteractions(callbackAdapter);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InvalidRequest")
                .operationStatus(OperationStatus.FAILED)
                .message("Model validation failed (#: extraneous key [fieldCausesValidationError] is not permitted)").build());
        }
    }

    @Test
    public void invokeHandler_withMalformedRequest_causesSchemaValidationFailure() throws IOException {
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        // our ObjectMapper implementation will ignore extraneous fields rather than
        // fail them
        // this slightly loosens the coupling between caller (CloudFormation) and
        // handlers.
        try (final InputStream in = loadRequestStream("malformed.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456")
                .operationStatus(OperationStatus.SUCCESS).resourceModel(TestModel.builder().build()).build());
        }
    }

    @Test
    public void invokeHandler_withoutPlatformCredentials_returnsFailure() throws IOException {
        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request-without-platform-credentials.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                    .operationStatus(OperationStatus.FAILED).message("Missing required platform credentials")
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_withoutCallerCredentials_passesNoAWSProxy() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request-without-caller-credentials.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.SUCCESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());

            // proxy should be null by virtue of having not had callerCredentials passed in
            assertThat(wrapper.awsClientProxy).isNull();
        }
    }

    @Test
    public void invokeHandler_withDefaultInjection_returnsSuccess() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.SUCCESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());

            // proxy uses caller credentials and will be injected
            assertThat(wrapper.awsClientProxy).isNotNull();
        }
    }

    @Test
    public void invokeHandler_withDefaultInjection_returnsInProgress() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();
            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.IN_PROGRESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_failToRescheduleInvocation() throws IOException {
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        doThrow(new AmazonServiceException("some error")).when(scheduler).rescheduleAfterMinutes(anyString(), anyInt(),
            ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with in progress status to trigger callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                    .operationStatus(OperationStatus.FAILED)
                    .message("some error (Service: null; Status Code: 0; Error Code: null; Request ID: null)")
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_clientsRefreshedOnEveryInvoke() throws IOException {
        Context context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        verify(callbackAdapter, times(1)).refreshClient();
        verify(platformMetricsPublisher, times(1)).refreshClient();
        verify(scheduler, times(1)).refreshClient();

        // invoke the same wrapper instance again to ensure client is refreshed
        context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        verify(callbackAdapter, times(2)).refreshClient();
        verify(platformMetricsPublisher, times(2)).refreshClient();
        verify(scheduler, times(2)).refreshClient();
    }

    @Test
    public void invokeHandler_platformCredentialsRefreshedOnEveryInvoke() throws IOException {
        Context context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        final Credentials expected = new Credentials("32IEHAHFIAG538KYASAI", "0O2hop/5vllVHjbA8u52hK8rLcroZpnL5NPGOi66",
                                                     "gqe6eIsFPHOlfhc3RKl5s5Y6Dy9PYvN1CEYsswz5TQUsE8WfHD6LPK549euXm4Vn4INBY9nMJ1cJe2mxTYFdhWHSnkOQv2SHemal");
        verify(platformCredentialsProvider, times(1)).setCredentials(eq(expected));
        // invoke the same wrapper instance again to ensure client is refreshed
        context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.with-new-credentials.json");
            OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        final Credentials expectedNew = new Credentials("GT530IJDHALYZQSZZ8XG", "UeJEwC/dqcYEn2viFd5TjKjR5TaMOfdeHrlLXxQL",
                                                        "469gs8raWJCaZcItXhGJ7dt3urI13fOTcde6ibhuHJz6r6bRRCWvLYGvCsqrN8WUClYL9lxZHymrWXvZ9xN0GoI2LFdcAAinZk5t");

        verify(platformCredentialsProvider, times(1)).setCredentials(eq(expectedNew));
    }

    @Test
    public void invokeHandler_withNoResponseEndpoint_returnsFailure() throws IOException {
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).resourceModel(model).build();
        wrapper.setInvokeHandlerResponse(pe);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // our ObjectMapper implementation will ignore extraneous fields rather than
        // fail them
        // this slightly loosens the coupling between caller (CloudFormation) and
        // handlers.
        try (final InputStream in = loadRequestStream("no-response-endpoint.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // malformed input exception is published
            verify(lambdaLogger, times(1)).log(anyString());
            verifyNoMoreInteractions(platformMetricsPublisher);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                    .operationStatus(OperationStatus.FAILED).message("No callback endpoint received")
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_localReinvokeWithSufficientRemainingTime() throws IOException {
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel,
            TestContext> pe1 = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.IN_PROGRESS) // iterate
                                                                                                                   // locally once
                .callbackDelaySeconds(5).resourceModel(model).build();
        final ProgressEvent<TestModel,
            TestContext> pe2 = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.SUCCESS) // then exit loop
                .resourceModel(model).build();
        wrapper.enqueueResponses(Arrays.asList(pe1, pe2));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.with-request-context.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            final Context context = getLambdaContext();
            // give enough time to invoke again locally
            when(context.getRemainingTimeInMillis()).thenReturn(75000);

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(platformMetricsPublisher, times(2)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(providerMetricsPublisher, times(2)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);
            verifyNoMoreInteractions(providerMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be"));

            // re-invocation via CloudWatch should NOT occur for <60 when Lambda remaining
            // time allows
            verifyNoMoreInteractions(scheduler);

            final ArgumentCaptor<String> bearerTokenCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<HandlerErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(HandlerErrorCode.class);
            final ArgumentCaptor<OperationStatus> operationStatusCaptor = ArgumentCaptor.forClass(OperationStatus.class);
            final ArgumentCaptor<TestModel> resourceModelCaptor = ArgumentCaptor.forClass(TestModel.class);
            final ArgumentCaptor<String> statusMessageCaptor = ArgumentCaptor.forClass(String.class);

            verify(callbackAdapter, times(2)).reportProgress(bearerTokenCaptor.capture(), errorCodeCaptor.capture(),
                operationStatusCaptor.capture(), operationStatusCaptor.capture(), resourceModelCaptor.capture(),
                statusMessageCaptor.capture());

            final List<String> bearerTokens = bearerTokenCaptor.getAllValues();
            final List<HandlerErrorCode> errorCodes = errorCodeCaptor.getAllValues();
            final List<OperationStatus> operationStatuses = operationStatusCaptor.getAllValues();
            final List<TestModel> resourceModels = resourceModelCaptor.getAllValues();
            final List<String> statusMessages = statusMessageCaptor.getAllValues();

            // CloudFormation should receive 2 callback invocation; once for IN_PROGRESS,
            // once for COMPLETION
            assertThat(bearerTokens).containsExactly("123456", "123456");
            assertThat(errorCodes).containsExactly(null, null);
            assertThat(resourceModels).containsExactly(TestModel.builder().property1("abc").property2(123).build(),
                TestModel.builder().property1("abc").property2(123).build());
            assertThat(statusMessages).containsExactly(null, null);
            assertThat(operationStatuses).containsExactly(OperationStatus.IN_PROGRESS, OperationStatus.IN_PROGRESS,
                OperationStatus.SUCCESS, OperationStatus.IN_PROGRESS);

            // verify final output response is for success response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.SUCCESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_localReinvokeWithSufficientRemainingTimeForFirstIterationOnly_SchedulesViaCloudWatch()
        throws IOException {
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel,
            TestContext> pe1 = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.IN_PROGRESS) // iterate
                                                                                                                   // locally once
                .callbackDelaySeconds(5).resourceModel(model).build();
        final ProgressEvent<TestModel,
            TestContext> pe2 = ProgressEvent.<TestModel, TestContext>builder().status(OperationStatus.IN_PROGRESS) // second
                                                                                                                   // iteration
                                                                                                                   // will exceed
                                                                                                                   // runtime
                .callbackDelaySeconds(5).resourceModel(model).build();
        wrapper.enqueueResponses(Arrays.asList(pe1, pe2));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.with-request-context.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {

            final Context context = getLambdaContext();
            // first remaining time allows for a local reinvocation, whereas the latter will
            // force the second invocation to be via CWE
            when(context.getRemainingTimeInMillis()).thenReturn(70000, 5000);

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(platformMetricsPublisher, times(2)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(providerMetricsPublisher, times(2)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(platformMetricsPublisher);
            verifyNoMoreInteractions(providerMetricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

            // re-invocation via CloudWatch should occur for the second iteration
            verify(scheduler, times(1)).rescheduleAfterMinutes(anyString(), eq(0),
                ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be"));

            final ArgumentCaptor<String> bearerTokenCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<HandlerErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(HandlerErrorCode.class);
            final ArgumentCaptor<OperationStatus> operationStatusCaptor = ArgumentCaptor.forClass(OperationStatus.class);
            final ArgumentCaptor<TestModel> resourceModelCaptor = ArgumentCaptor.forClass(TestModel.class);
            final ArgumentCaptor<String> statusMessageCaptor = ArgumentCaptor.forClass(String.class);

            verify(callbackAdapter, times(2)).reportProgress(bearerTokenCaptor.capture(), errorCodeCaptor.capture(),
                operationStatusCaptor.capture(), operationStatusCaptor.capture(), resourceModelCaptor.capture(),
                statusMessageCaptor.capture());

            final List<String> bearerTokens = bearerTokenCaptor.getAllValues();
            final List<HandlerErrorCode> errorCodes = errorCodeCaptor.getAllValues();
            final List<OperationStatus> operationStatuses = operationStatusCaptor.getAllValues();
            final List<TestModel> resourceModels = resourceModelCaptor.getAllValues();
            final List<String> statusMessages = statusMessageCaptor.getAllValues();

            // CloudFormation should receive 2 callback invocation; both for IN_PROGRESS
            assertThat(bearerTokens).containsExactly("123456", "123456");
            assertThat(errorCodes).containsExactly(null, null);
            assertThat(resourceModels).containsExactly(TestModel.builder().property1("abc").property2(123).build(),
                TestModel.builder().property1("abc").property2(123).build());
            assertThat(statusMessages).containsExactly(null, null);
            assertThat(operationStatuses).containsExactly(OperationStatus.IN_PROGRESS, OperationStatus.IN_PROGRESS,
                OperationStatus.IN_PROGRESS, OperationStatus.IN_PROGRESS);

            // verify final output response is for second IN_PROGRESS response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").operationStatus(OperationStatus.IN_PROGRESS)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_throwsAmazonServiceException_returnsServiceException() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new AmazonServiceException("some error"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(AmazonServiceException.class), any(HandlerErrorCode.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("GeneralServiceException")
                    .operationStatus(OperationStatus.FAILED)
                    .message("some error (Service: null; Status Code: 0; Error Code: null; Request ID: null)").build());
        }
    }

    @Test
    public void invokeHandler_throwsResourceAlreadyExistsException_returnsAlreadyExists() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new ResourceAlreadyExistsException("AWS::Test::TestModel", "id-1234"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(ResourceAlreadyExistsException.class), any(HandlerErrorCode.class));

            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(ResourceAlreadyExistsException.class), any(HandlerErrorCode.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("AlreadyExists")
                    .operationStatus(OperationStatus.FAILED)
                    .message("Resource of type 'AWS::Test::TestModel' with identifier 'id-1234' already exists.").build());
        }
    }

    @Test
    public void invokeHandler_throwsResourceNotFoundException_returnsNotFound() throws IOException {
        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new ResourceNotFoundException("AWS::Test::TestModel", "id-1234"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(platformMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(platformMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            verify(providerMetricsPublisher, times(1)).publishInvocationMetric(any(Instant.class), eq(Action.CREATE));
            verify(providerMetricsPublisher, times(1)).publishDurationMetric(any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(platformMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(ResourceNotFoundException.class), any(HandlerErrorCode.class));

            verify(providerMetricsPublisher, times(1)).publishExceptionMetric(any(Instant.class), any(),
                any(ResourceNotFoundException.class), any(HandlerErrorCode.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(any(JSONObject.class), any(JSONObject.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("NotFound")
                    .operationStatus(OperationStatus.FAILED)
                    .message("Resource of type 'AWS::Test::TestModel' with identifier 'id-1234' was not found.").build());
        }
    }

    @Test
    public void invokeHandler_metricPublisherThrowable_returnsFailureResponse() throws IOException {
        // simulate runtime Errors in the metrics publisher (such as dependency
        // resolution conflicts)
        doThrow(new Error("not an Exception")).when(platformMetricsPublisher).publishInvocationMetric(any(), any());
        doThrow(new Error("not an Exception")).when(platformMetricsPublisher).publishExceptionMetric(any(), any(), any(), any());

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            try {
                wrapper.handleRequest(in, out, context);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // no further calls to metrics publisher should occur
            verifyNoMoreInteractions(platformMetricsPublisher);
            verifyNoMoreInteractions(providerMetricsPublisher);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                    .operationStatus(OperationStatus.FAILED).message("not an Exception")
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void invokeHandler_withInvalidPayload_returnsFailureResponse() throws IOException {
        try (final InputStream in = new ByteArrayInputStream(new byte[0]); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            try {
                wrapper.handleRequest(in, out, context);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().errorCode("InternalFailure").operationStatus(OperationStatus.FAILED)
                    .message("A JSONObject text must begin with '{' at 0 [character 1 line 1]").build());
        }
    }

    @Test
    public void invokeHandler_withNullInputStream_returnsFailureResponse() throws IOException {
        try (final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            try {
                wrapper.handleRequest(null, out, context);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED).message("No request object received").build());
        }
    }

    @Test
    public void invokeHandler_withEmptyPayload_returnsFailure() throws IOException {
        try (final InputStream in = loadRequestStream("empty.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            try {
                wrapper.handleRequest(in, out, context);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED).message("Invalid request object received").build());
        }
    }

    @Test
    public void invokeHandler_withEmptyResourceProperties_returnsFailure() throws IOException {
        try (final InputStream in = loadRequestStream("empty.resource.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            try {
                wrapper.handleRequest(in, out, context);
            } catch (final Error e) {
                // ignore so we can perform verifications
            }

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().errorCode("InternalFailure").bearerToken("123456")
                .operationStatus(OperationStatus.FAILED).message("Invalid resource properties object received").build());
        }
    }

    @Test
    public void stringifiedPayload_validation_successful() throws IOException {
        // this test ensures that validation on the resource payload is performed
        // against the serialized
        // model rather than the raw payload. This allows the handlers to accept
        // incoming payloads that
        // may have quoted values where the JSON Serialization is still able to
        // construct a valid POJO
        SchemaValidator validator = new Validator();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, platformCredentialsProvider,
                                                            providerLoggingCredentialsProvider, platformEventsLogger,
                                                            providerEventsLogger, platformMetricsPublisher,
                                                            providerMetricsPublisher, scheduler, validator, httpClient);

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS).message("Handler was invoked").build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request-with-stringified-resource.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder().bearerToken("123456").message("Handler was invoked")
                .operationStatus(OperationStatus.SUCCESS).build());

        }
    }

    @Test
    public void handleRequest_apiThrowsOperationStatusException_returnsFailedStatus() throws IOException {
        final String errorMessage = "Unexpected status";
        final OperationStatusCheckFailedException exception = OperationStatusCheckFailedException.builder().message(errorMessage)
            .build();

        // simulate runtime Errors in the callback adapter (such as multiple handlers
        // are invoked
        // for single task by CloudFormation)
        doThrow(exception).when(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.IN_PROGRESS),
            eq(OperationStatus.PENDING), any(), any());

        try (final InputStream in = loadRequestStream("create.request.json");
            final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();
            // only calls to callback adapter to acknowledge the task
            verify(callbackAdapter).reportProgress(any(), any(), eq(OperationStatus.IN_PROGRESS), eq(OperationStatus.PENDING),
                any(), any());

            // no further calls to callback adapter should occur
            verifyNoMoreInteractions(callbackAdapter);

            // verify output response
            verifyHandlerResponse(out,
                HandlerResponse.<TestModel>builder().bearerToken("123456").errorCode("InternalFailure")
                    .operationStatus(OperationStatus.FAILED).message(errorMessage)
                    .resourceModel(TestModel.builder().property1("abc").property2(123).build()).build());
        }
    }

    @Test
    public void getDesiredResourceTags_oneStackTagAndOneResourceTag() {
        final Map<String, String> stackTags = new HashMap<>();
        stackTags.put("Tag1", "Value1");

        final Map<String, String> resourceTags = new HashMap<>();
        resourceTags.put("Tag2", "Value2");
        final TestModel model = TestModel.builder().tags(resourceTags).build();

        final HandlerRequest<TestModel, TestContext> request = new HandlerRequest<>();
        final RequestData<TestModel> requestData = new RequestData<>();
        requestData.setResourceProperties(model);
        requestData.setStackTags(stackTags);
        request.setRequestData(requestData);

        final Map<String, String> tags = wrapper.getDesiredResourceTags(request);
        assertThat(tags).isNotNull();
        assertThat(tags.size()).isEqualTo(2);
        assertThat(tags.get("Tag1")).isEqualTo("Value1");
        assertThat(tags.get("Tag2")).isEqualTo("Value2");
    }

    @Test
    public void getDesiredResourceTags_resourceTagOverridesStackTag() {
        final Map<String, String> stackTags = new HashMap<>();
        stackTags.put("Tag1", "Value1");

        final Map<String, String> resourceTags = new HashMap<>();
        resourceTags.put("Tag1", "Value2");
        final TestModel model = TestModel.builder().tags(resourceTags).build();

        final HandlerRequest<TestModel, TestContext> request = new HandlerRequest<>();
        final RequestData<TestModel> requestData = new RequestData<>();
        requestData.setResourceProperties(model);
        requestData.setStackTags(stackTags);
        request.setRequestData(requestData);

        final Map<String, String> tags = wrapper.getDesiredResourceTags(request);
        assertThat(tags).isNotNull();
        assertThat(tags.size()).isEqualTo(1);
        assertThat(tags.get("Tag1")).isEqualTo("Value2");
    }
}
