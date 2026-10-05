package at.ac.univie.dse.iot.msml.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A manually added comment on a stored result (metadata). */
@Entity
@Table(name = "ml_comment")
public class CommentEntity {

    /** Longest comment text. */
    public static final int MAX_TEXT_LENGTH = 2_000;
    /** Longest author name. */
    public static final int MAX_AUTHOR_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_id", nullable = false)
    private MlResultEntity result;

    @Column(name = "comment_text", nullable = false, length = MAX_TEXT_LENGTH)
    private String text;

    @Column(length = MAX_AUTHOR_LENGTH)
    private String author;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    /** For JPA. */
    protected CommentEntity() {
    }

    CommentEntity(MlResultEntity result, String text, String author, Instant createdAt) {
        this.result = result;
        this.text = text;
        this.author = author;
        this.createdAt = createdAt;
    }

    void edit(String newText, Instant now) {
        this.text = newText;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public MlResultEntity getResult() {
        return result;
    }

    public String getText() {
        return text;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
