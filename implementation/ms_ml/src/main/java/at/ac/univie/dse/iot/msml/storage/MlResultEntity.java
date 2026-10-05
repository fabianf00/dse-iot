package at.ac.univie.dse.iot.msml.storage;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A stored ML result: an anomaly or a prediction change. Single-table inheritance, so comments can
 * reference any result, and lists across both kinds are one query.
 *
 * <p>{@code (mlInstance, serverEpoch, eventSequence)} identifies the event the result was recorded
 * from; the unique constraint makes recording idempotent.
 */
@Entity
@Table(name = "ml_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_ml_result_event",
                columnNames = {"ml_instance", "server_epoch", "event_sequence"}),
        indexes = {
            @Index(name = "ix_ml_result_type_time", columnList = "kind, data_type, occurred_at"),
            @Index(name = "ix_ml_result_chain", columnList = "chain_id")
        })
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING, length = 16)
public abstract class MlResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String mlInstance;

    @Column(nullable = false)
    private long serverEpoch;

    @Column(nullable = false)
    private long eventSequence;

    @Column(nullable = false, length = 64)
    private String dataType;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant recordedAt;

    @OneToMany(mappedBy = "result", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<CommentEntity> comments = new ArrayList<>();

    /** For JPA. */
    protected MlResultEntity() {
    }

    protected MlResultEntity(String mlInstance, long serverEpoch, long eventSequence, String dataType,
            Instant occurredAt, Instant recordedAt) {
        this.mlInstance = mlInstance;
        this.serverEpoch = serverEpoch;
        this.eventSequence = eventSequence;
        this.dataType = dataType;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public abstract ResultKind kind();

    public Long getId() {
        return id;
    }

    public String getMlInstance() {
        return mlInstance;
    }

    public long getServerEpoch() {
        return serverEpoch;
    }

    public long getEventSequence() {
        return eventSequence;
    }

    public String getDataType() {
        return dataType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public List<CommentEntity> getComments() {
        return comments;
    }

    CommentEntity addComment(String text, String author, Instant now) {
        CommentEntity comment = new CommentEntity(this, text, author, now);
        comments.add(comment);
        return comment;
    }
}
