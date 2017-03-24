/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.TaskOperationFailure;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.tasks.BaseTasksRequest;
import org.elasticsearch.action.support.tasks.BaseTasksResponse;
import org.elasticsearch.action.support.tasks.TransportTasksAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.utils.DatafeedStateObserver;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;
import org.elasticsearch.xpack.persistent.RemovePersistentTaskAction;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class StopDatafeedAction
        extends Action<StopDatafeedAction.Request, StopDatafeedAction.Response, StopDatafeedAction.RequestBuilder> {

    public static final StopDatafeedAction INSTANCE = new StopDatafeedAction();
    public static final String NAME = "cluster:admin/ml/datafeeds/stop";
    public static final ParseField TIMEOUT = new ParseField("timeout");
    public static final ParseField FORCE = new ParseField("force");

    private StopDatafeedAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public Response newResponse() {
        return new Response();
    }

    public static class Request extends BaseTasksRequest<Request> implements ToXContent {

        public static ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, datafeedId) -> request.datafeedId = datafeedId, DatafeedConfig.ID);
            PARSER.declareString((request, val) ->
                    request.setTimeout(TimeValue.parseTimeValue(val, TIMEOUT.getPreferredName())), TIMEOUT);
            PARSER.declareBoolean(Request::setForce, FORCE);
        }

        public static Request fromXContent(XContentParser parser) {
            return parseRequest(null, parser);
        }

        public static Request parseRequest(String datafeedId, XContentParser parser) {
            Request request = PARSER.apply(parser, null);
            if (datafeedId != null) {
                request.datafeedId = datafeedId;
            }
            return request;
        }

        private String datafeedId;
        private boolean force = false;

        public Request(String jobId) {
            this.datafeedId = ExceptionsHelper.requireNonNull(jobId, DatafeedConfig.ID.getPreferredName());
            setActions(StartDatafeedAction.NAME);
            setTimeout(TimeValue.timeValueSeconds(20));
        }

        Request() {
        }

        public String getDatafeedId() {
            return datafeedId;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }

        @Override
        public boolean match(Task task) {
            String expectedDescription = "datafeed-" + datafeedId;
            return task instanceof StartDatafeedAction.DatafeedTask && expectedDescription.equals(task.getDescription());
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            datafeedId = in.readString();
            force = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(datafeedId);
            out.writeBoolean(force);
        }

        @Override
        public int hashCode() {
            return Objects.hash(datafeedId, getTimeout());
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(DatafeedConfig.ID.getPreferredName(), datafeedId);
            if (getTimeout() != null) {
                builder.field(TIMEOUT.getPreferredName(), getTimeout().getStringRep());
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equals(datafeedId, other.datafeedId) &&
                    Objects.equals(getTimeout(), other.getTimeout()) &&
                    Objects.equals(force, other.force);
        }
    }

    public static class Response extends BaseTasksResponse implements Writeable {

        private boolean stopped;

        public Response(boolean stopped) {
            super(null, null);
            this.stopped = stopped;
        }

        public Response(StreamInput in) throws IOException {
            readFrom(in);
        }

        public Response() {
        }

        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            stopped = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBoolean(stopped);
        }
    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client, StopDatafeedAction action) {
            super(client, action, new Request());
        }
    }

    public static class TransportAction extends TransportTasksAction<StartDatafeedAction.DatafeedTask, Request, Response, Response> {

        private final Client client;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ThreadPool threadPool,
                               ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                               ClusterService clusterService, Client client) {
            super(settings, StopDatafeedAction.NAME, threadPool, clusterService, transportService, actionFilters,
                    indexNameExpressionResolver, Request::new, Response::new, MachineLearning.THREAD_POOL_NAME);
            this.client = client;
        }

        @Override
        protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
            ClusterState state = clusterService.state();
            MetaData metaData = state.metaData();
            PersistentTasksCustomMetaData tasks = metaData.custom(PersistentTasksCustomMetaData.TYPE);

            if (request.force) {
                PersistentTask<?> datafeedTask = MlMetadata.getDatafeedTask(request.getDatafeedId(), tasks);
                if (datafeedTask != null) {
                    forceStopTask(client, datafeedTask.getId(), listener);
                } else {
                    String msg = "Requested datafeed [" + request.getDatafeedId() + "] be force-stopped, but " +
                            "datafeed's task could not be found.";
                    logger.warn(msg);
                    listener.onFailure(new RuntimeException(msg));
                }
            } else {
                MlMetadata mlMetadata = metaData.custom(MlMetadata.TYPE);
                String nodeId = validateAndReturnNodeId(request.getDatafeedId(), mlMetadata, tasks);
                request.setNodes(nodeId);
                ActionListener<Response> finalListener =
                        ActionListener.wrap(r -> waitForDatafeedStopped(request, r, listener), listener::onFailure);
                super.doExecute(task, request, finalListener);
            }
        }

        // Wait for datafeed to be marked as stopped in cluster state, which means the datafeed persistent task has been removed
        // This api returns when task has been cancelled, but that doesn't mean the persistent task has been removed from cluster state,
        // so wait for that to happen here.
        void waitForDatafeedStopped(Request request, Response response, ActionListener<Response> listener) {
            DatafeedStateObserver observer = new DatafeedStateObserver(threadPool, clusterService);
            observer.waitForState(request.getDatafeedId(), request.getTimeout(), DatafeedState.STOPPED, e -> {
                if (e != null) {
                    listener.onFailure(e);
                } else {
                    listener.onResponse(response);
                }
            });
        }

        private void forceStopTask(Client client, long taskId, ActionListener<Response> listener) {
            RemovePersistentTaskAction.Request request = new RemovePersistentTaskAction.Request(taskId);

            client.execute(RemovePersistentTaskAction.INSTANCE, request,
                    ActionListener.wrap(
                            response -> listener.onResponse(new Response(response.isAcknowledged())),
                            listener::onFailure));
        }

        @Override
        protected Response newResponse(Request request, List<Response> tasks, List<TaskOperationFailure> taskOperationFailures,
                                       List<FailedNodeException> failedNodeExceptions) {
            return TransportJobTaskAction.selectFirst(tasks, taskOperationFailures, failedNodeExceptions);
        }

        @Override
        protected Response readTaskResponse(StreamInput in) throws IOException {
            return new Response(in);
        }

        @Override
        protected void taskOperation(Request request, StartDatafeedAction.DatafeedTask task, ActionListener<Response> listener) {
            task.stop("stop_datafeed_api", request.getTimeout());
            listener.onResponse(new Response(true));
        }

        @Override
        protected boolean accumulateExceptions() {
            return true;
        }
    }

    static String validateAndReturnNodeId(String datafeedId, MlMetadata mlMetadata, PersistentTasksCustomMetaData tasks) {
        DatafeedConfig datafeed = mlMetadata.getDatafeed(datafeedId);
        if (datafeed == null) {
            throw new ResourceNotFoundException(Messages.getMessage(Messages.DATAFEED_NOT_FOUND, datafeedId));
        }
        PersistentTask<?> task = MlMetadata.getDatafeedTask(datafeedId, tasks);
        if (task == null || task.getStatus() != DatafeedState.STARTED) {
            throw new ElasticsearchStatusException("datafeed already stopped, expected datafeed state [{}], but got [{}]",
                    RestStatus.CONFLICT, DatafeedState.STARTED, DatafeedState.STOPPED);
        }
        return task.getExecutorNode();
    }
}
