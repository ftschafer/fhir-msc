package ca.uhn.fhir.jpa.starter.common;

import java.time.Instant;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "NEWS2_AGG")
public class News2Aggregate implements Serializable {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "PATIENT_ID", length = 64)
        public String patientId;

        @Column(name = "CODE", length = 64)
        public String code;

        public Id() {}
        public Id(String patientId, String code) { this.patientId = patientId; this.code = code; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id)) return false;
            Id that = (Id)o;
            return Objects.equals(patientId, that.patientId) && Objects.equals(code, that.code);
        }
        @Override public int hashCode() { return Objects.hash(patientId, code); }
    }

    @EmbeddedId
    private Id id;

    // rename from "value" to "score" and avoid using reserved words
    @Column(name = "NEWS2_VALUE")
    private Integer score;

    @Column(name = "OBS_INSTANT")
    private Instant observedAt;

    public News2Aggregate() {}
    public News2Aggregate(String patientId, String code, Integer score, Instant observedAt) {
        this.id = new Id(patientId, code);
        this.score = score;
        this.observedAt = observedAt;
    }

    public String getPatientId() { return id == null ? null : id.patientId; }
    public String getCode() { return id == null ? null : id.code; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public void setValue(int value) {
        this.score = value;
    }
}
