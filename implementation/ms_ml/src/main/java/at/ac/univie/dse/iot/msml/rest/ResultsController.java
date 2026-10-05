package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.msml.rest.Resources.DeletedResource;
import at.ac.univie.dse.iot.msml.rest.Resources.PageResource;
import at.ac.univie.dse.iot.msml.results.ResultService;
import at.ac.univie.dse.iot.msml.storage.Page;
import at.ac.univie.dse.iot.msml.storage.PageRequest;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.StoredResult;
import io.javalin.http.Context;
import java.util.Locale;

/** {@code /anomalies} or {@code /predictions}: list, get and delete stored results of one kind. */
final class ResultsController {

    private final ResultKind kind;
    private final ResultService results;

    ResultsController(ResultKind kind, ResultService results) {
        this.kind = kind;
        this.results = results;
    }

    /** {@code GET /<kind>?<filter>&limit&offset&sort} */
    void list(Context ctx) {
        ResultFilter filter = FilterParser.filterForList(kind, ctx.queryParamMap());
        PageRequest page = FilterParser.page(ctx.queryParamMap());
        Page<StoredResult> found = results.search(filter, page);
        ctx.json(new PageResource<>(found.items().stream().map(ResourceMapper::toResource).toList(),
                found.total(), found.limit(), found.offset()));
    }

    /** {@code GET /<kind>/{id}} */
    void get(Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        StoredResult result = results.find(kind, id).orElseThrow(() -> notFound(id));
        ctx.json(ResourceMapper.toResource(result));
    }

    /** {@code DELETE /<kind>/{id}} */
    void delete(Context ctx) {
        long id = Ids.parse(ctx.pathParam("id"));
        if (!results.delete(kind, id)) {
            throw notFound(id);
        }
        ctx.status(204);
    }

    /** {@code DELETE /<kind>?<filter>}: at least one filter is required. */
    void deleteMatching(Context ctx) {
        ResultFilter filter = FilterParser.filterForDelete(kind, ctx.queryParamMap());
        ctx.json(new DeletedResource(results.deleteMatching(filter)));
    }

    ApiException notFound(long id) {
        return ApiException.notFound(name() + " " + id + " does not exist");
    }

    String name() {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
