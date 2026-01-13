package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "NEWS2_BLOCK_AGG",
    indexes = {
        @Index(name = "idx_news2_city", columnList = "CITY"),
        @Index(name = "idx_news2_neighborhood", columnList = "NEIGHBORHOOD")
    }
)
public class BlockNews2Aggregate {

    // Replace single @Id with composite @EmbeddedId
    @EmbeddedId
    private BlockKey id;

    @Column(name = "TOTAL_SCORE")
    private int totalScore;

    @Column(name = "PATIENT_COUNT")
    private int patientCount;

    @Column(name = "UPDATED_AT")
    private Instant updatedAt;

    public BlockNews2Aggregate() {}

    public BlockNews2Aggregate(String region, String city, String block) {
        this.id = new BlockKey(region, city, block);
        this.updatedAt = Instant.now();
    }


     // Accessors via id
    public String getBlock() { return id == null ? null : id.getBlock(); }
    public String getCity() { return id == null ? null : id.getCity(); }
    public String getNeighborhood() { return id == null ? null : id.getNeighborhood(); }
    public void setId(BlockKey id) { this.id = id; }

    public int getTotalScore() { return totalScore; }
    public int getPatientCount() { return patientCount; }
    public Instant getUpdatedAt() { return updatedAt; }
    public double getAverage() { return patientCount == 0 ? 0.0 : (double) totalScore / patientCount; }

    public void setTotalScore(int totalScore) { this.totalScore = Math.max(totalScore, 0); }
    public void setPatientCount(int patientCount) { this.patientCount = Math.max(patientCount, 0); }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void applyDelta(int scoreDelta, int patientDelta) {
        this.totalScore = Math.max(0, this.totalScore + scoreDelta);
        this.patientCount = Math.max(0, this.patientCount + patientDelta);
        this.updatedAt = Instant.now();
    }
}
