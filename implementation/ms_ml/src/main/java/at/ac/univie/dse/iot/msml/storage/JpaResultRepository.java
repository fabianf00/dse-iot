package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredAnomaly;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredPhase;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredPrediction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ResultRepository} on JPA. No SQL or JPQL: single objects through
 * {@code find/persist/remove}, everything else through the Criteria API.
 *
 * <p>Lists load one page of results, then the comments of exactly those results in a second
 * query (no N+1 queries, no paging over a collection join).
 */
public final class JpaResultRepository implements ResultRepository {

    /** Bulk deletes remove results in chunks of this many ids. */
    private static final int DELETE_CHUNK = 500;

    private final Database database;

    public JpaResultRepository(Database database) {
        this.database = database;
    }

    @Override
    public boolean record(MlResultEntity result) {
        return database.inTransaction(em -> {
            if (isRecorded(em, result)) {
                return false;
            }
            em.persist(result);
            return true;
        });
    }

    @Override
    public Optional<StoredResult> find(ResultKind kind, long id) {
        return database.inTransaction(em -> Optional.ofNullable(em.find(entityClass(kind), id))
                .map(result -> toStored(result, result.getComments().stream().map(JpaResultRepository::toStored).toList())));
    }

    @Override
    public Page<StoredResult> search(ResultFilter filter, PageRequest page) {
        return database.inTransaction(em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<MlResultEntity> query = cb.createQuery(MlResultEntity.class);
            Root<MlResultEntity> root = query.from(MlResultEntity.class);
            query.select(root).where(predicates(cb, query, root, filter)).orderBy(order(cb, root, page.order()));
            List<MlResultEntity> results = em.createQuery(query)
                    .setFirstResult(page.offset())
                    .setMaxResults(page.limit())
                    .getResultList();
            Map<Long, List<StoredComment>> comments = commentsOf(em, results.stream().map(MlResultEntity::getId).toList());
            List<StoredResult> items = results.stream()
                    .map(result -> toStored(result, comments.getOrDefault(result.getId(), List.of())))
                    .toList();
            return new Page<>(items, count(em, filter), page.limit(), page.offset());
        });
    }

    @Override
    public long count(ResultFilter filter) {
        return database.inTransaction(em -> count(em, filter));
    }

    @Override
    public boolean delete(ResultKind kind, long id) {
        return database.inTransaction(em -> {
            MlResultEntity result = em.find(entityClass(kind), id);
            if (result == null) {
                return false;
            }
            em.remove(result);
            return true;
        });
    }

    @Override
    public long deleteMatching(ResultFilter filter) {
        return database.inTransaction(em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Long> idQuery = cb.createQuery(Long.class);
            Root<MlResultEntity> root = idQuery.from(MlResultEntity.class);
            idQuery.select(root.get("id")).where(predicates(cb, idQuery, root, filter));
            List<Long> ids = em.createQuery(idQuery).getResultList();
            long deleted = 0;
            for (int start = 0; start < ids.size(); start += DELETE_CHUNK) {
                List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + DELETE_CHUNK));
                CriteriaDelete<CommentEntity> deleteComments = cb.createCriteriaDelete(CommentEntity.class);
                Root<CommentEntity> comment = deleteComments.from(CommentEntity.class);
                deleteComments.where(comment.get("result").get("id").in(chunk));
                em.createQuery(deleteComments).executeUpdate();

                CriteriaDelete<MlResultEntity> deleteResults = cb.createCriteriaDelete(MlResultEntity.class);
                Root<MlResultEntity> result = deleteResults.from(MlResultEntity.class);
                deleteResults.where(result.get("id").in(chunk));
                deleted += em.createQuery(deleteResults).executeUpdate();
            }
            return deleted;
        });
    }

    @Override
    public Optional<List<StoredComment>> comments(ResultKind kind, long resultId) {
        return database.inTransaction(em -> Optional.ofNullable(em.find(entityClass(kind), resultId))
                .map(result -> result.getComments().stream().map(JpaResultRepository::toStored).toList()));
    }

    @Override
    public Optional<StoredComment> addComment(ResultKind kind, long resultId, String text, String author, Instant now) {
        return database.inTransaction(em -> {
            MlResultEntity result = em.find(entityClass(kind), resultId);
            if (result == null) {
                return Optional.<StoredComment>empty();
            }
            CommentEntity comment = result.addComment(text, author, now);
            em.persist(comment);
            return Optional.of(toStored(comment));
        });
    }

    @Override
    public Optional<StoredComment> updateComment(long commentId, String text, Instant now) {
        return database.inTransaction(em -> {
            CommentEntity comment = em.find(CommentEntity.class, commentId);
            if (comment == null) {
                return Optional.<StoredComment>empty();
            }
            comment.edit(text, now);
            return Optional.of(toStored(comment));
        });
    }

    @Override
    public boolean deleteComment(long commentId) {
        return database.inTransaction(em -> {
            CommentEntity comment = em.find(CommentEntity.class, commentId);
            if (comment == null) {
                return false;
            }
            comment.getResult().getComments().remove(comment);
            em.remove(comment);
            return true;
        });
    }

    // ---- queries ----

    private static boolean isRecorded(EntityManager em, MlResultEntity result) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<MlResultEntity> root = query.from(MlResultEntity.class);
        query.select(cb.count(root)).where(
                cb.equal(root.get("mlInstance"), result.getMlInstance()),
                cb.equal(root.get("serverEpoch"), result.getServerEpoch()),
                cb.equal(root.get("eventSequence"), result.getEventSequence()));
        return em.createQuery(query).getSingleResult() > 0;
    }

    private static long count(EntityManager em, ResultFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<MlResultEntity> root = query.from(MlResultEntity.class);
        query.select(cb.count(root)).where(predicates(cb, query, root, filter));
        return em.createQuery(query).getSingleResult();
    }

    private static Predicate[] predicates(CriteriaBuilder cb, AbstractQuery<?> query, Root<MlResultEntity> root,
            ResultFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.kind() != null) {
            predicates.add(cb.equal(root.type(), entityClass(filter.kind())));
        }
        if (!filter.dataTypes().isEmpty()) {
            predicates.add(root.get("dataType").in(filter.dataTypes()));
        }
        if (filter.chainId() != null) {
            predicates.add(cb.equal(cb.treat(root, AnomalyEntity.class).get("chainId"), filter.chainId()));
        }
        if (filter.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.<Instant>get("occurredAt"), filter.to()));
        }
        if (filter.commented() != null) {
            Subquery<Long> comments = query.subquery(Long.class);
            Root<CommentEntity> comment = comments.from(CommentEntity.class);
            comments.select(comment.get("id")).where(cb.equal(comment.get("result"), root));
            predicates.add(filter.commented() ? cb.exists(comments) : cb.not(cb.exists(comments)));
        }
        if (filter.mlInstance() != null) {
            predicates.add(cb.equal(root.get("mlInstance"), filter.mlInstance()));
        }
        if (filter.recordedBefore() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.<Instant>get("recordedAt"), filter.recordedBefore()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> order(CriteriaBuilder cb, Root<MlResultEntity> root, PageRequest.SortOrder order) {
        return order == PageRequest.SortOrder.NEWEST_FIRST
                ? List.of(cb.desc(root.get("occurredAt")), cb.desc(root.get("id")))
                : List.of(cb.asc(root.get("occurredAt")), cb.asc(root.get("id")));
    }

    /** Comments of the given results, grouped by result id, oldest first. */
    private static Map<Long, List<StoredComment>> commentsOf(EntityManager em, Collection<Long> resultIds) {
        Map<Long, List<StoredComment>> byResult = new LinkedHashMap<>();
        if (resultIds.isEmpty()) {
            return byResult;
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CommentEntity> query = cb.createQuery(CommentEntity.class);
        Root<CommentEntity> comment = query.from(CommentEntity.class);
        query.select(comment)
                .where(comment.get("result").get("id").in(resultIds))
                .orderBy(cb.asc(comment.get("createdAt")), cb.asc(comment.get("id")));
        for (CommentEntity entity : em.createQuery(query).getResultList()) {
            byResult.computeIfAbsent(entity.getResult().getId(), id -> new ArrayList<>()).add(toStored(entity));
        }
        return byResult;
    }

    // ---- mapping ----

    private static Class<? extends MlResultEntity> entityClass(ResultKind kind) {
        return kind == ResultKind.ANOMALY ? AnomalyEntity.class : PredictionEntity.class;
    }

    private static StoredResult toStored(MlResultEntity result, List<StoredComment> comments) {
        if (result instanceof AnomalyEntity anomaly) {
            return new StoredAnomaly(anomaly.getId(), anomaly.getMlInstance(), anomaly.getServerEpoch(),
                    anomaly.getEventSequence(), anomaly.getDataType(), anomaly.getOccurredAt(), anomaly.getRecordedAt(),
                    comments, anomaly.getChainId(), anomaly.getChainDescription(), anomaly.getExpected(),
                    anomaly.getObservedValue(), anomaly.getUnit(), anomaly.getObservedAt(), anomaly.getExpectedUntil(),
                    toStored(anomaly.getPast()), toStored(anomaly.getCurrent()));
        }
        PredictionEntity prediction = (PredictionEntity) result;
        return new StoredPrediction(prediction.getId(), prediction.getMlInstance(), prediction.getServerEpoch(),
                prediction.getEventSequence(), prediction.getDataType(), prediction.getOccurredAt(),
                prediction.getRecordedAt(), comments, prediction.getUnit(), prediction.getBinLower(),
                prediction.getBinUpper(), prediction.getMostLikelyValue(), prediction.getConfidence(),
                prediction.getSampleCount(), prediction.getPreviousBinLower(), prediction.getPreviousBinUpper());
    }

    private static StoredPhase toStored(PhaseObservationEmbeddable phase) {
        return phase == null ? null
                : new StoredPhase(phase.getCondition(), phase.getPeriodStart(), phase.getPeriodEnd(), phase.getSamples());
    }

    private static StoredComment toStored(CommentEntity comment) {
        return new StoredComment(comment.getId(), comment.getResult().getId(), comment.getText(), comment.getAuthor(),
                comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
