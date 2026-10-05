package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.json.JsonCodecException;
import at.ac.univie.dse.iot.msml.rest.Resources.CommentRequest;
import at.ac.univie.dse.iot.msml.rest.Resources.CommentResource;
import at.ac.univie.dse.iot.msml.results.ResultService;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import io.javalin.http.Context;
import java.util.List;
import java.util.Locale;

/** Comments of stored results: {@code /<kind>/{id}/comments} and {@code /comments/{commentId}}. */
final class CommentsController {

    private final ResultService results;

    CommentsController(ResultService results) {
        this.results = results;
    }

    /** {@code GET /<kind>/{id}/comments} */
    void list(ResultKind kind, Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        List<StoredComment> comments = results.comments(kind, id).orElseThrow(() -> resultNotFound(kind, id));
        ctx.json(comments.stream().map(ResourceMapper::toResource).toList());
    }

    /** {@code POST /<kind>/{id}/comments} with {@code {"text": "...", "author": "..."}} */
    void add(ResultKind kind, Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        CommentRequest request = body(ctx);
        StoredComment comment = results.addComment(kind, id, request.text(), request.author())
                .orElseThrow(() -> resultNotFound(kind, id));
        ctx.status(201).header("Location", RestServer.BASE_PATH + "/comments/" + comment.id());
        ctx.json(ResourceMapper.toResource(comment));
    }

    /** {@code PUT /comments/{commentId}} with {@code {"text": "..."}} */
    void update(Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        CommentRequest request = body(ctx);
        CommentResource comment = results.updateComment(id, request.text())
                .map(ResourceMapper::toResource)
                .orElseThrow(() -> ApiException.notFound("comment " + id + " does not exist"));
        ctx.json(comment);
    }

    /** {@code DELETE /comments/{commentId}} */
    void delete(Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        if (!results.deleteComment(id)) {
            throw ApiException.notFound("comment " + id + " does not exist");
        }
        ctx.status(204);
    }

    private static CommentRequest body(Context ctx) {
        String contentType = ctx.contentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ApiException(415, "Content-Type must be application/json");
        }
        try {
            CommentRequest request = JsonCodec.shared().fromJson(ctx.body(), CommentRequest.class);
            if (request == null) {
                throw ApiException.badRequest("body must be a JSON object with a text field");
            }
            return request;
        } catch (JsonCodecException e) {
            throw ApiException.badRequest("body must be a JSON object with a text field");
        }
    }

    private static ApiException resultNotFound(ResultKind kind, long id) {
        return ApiException.notFound(kind.name().toLowerCase(Locale.ROOT) + " " + id + " does not exist");
    }
}
