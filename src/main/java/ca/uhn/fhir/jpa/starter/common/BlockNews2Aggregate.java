package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "NEWS2_BLOCK_AGG")
public class BlockNews2Aggregate {
    @Id
    @Column(name = "BLOCK", length = 128)
    private String block;

    @Column(name = "TOTAL_SCORE")
    private int totalScore;

    @Column(name = "PATIENT_COUNT")
    private int patientCount;

    @Column(name = "UPDATED_AT")
    private Instant updatedAt;

    public BlockNews2Aggregate() {}
    public BlockNews2Aggregate(String block) {
        this.block = block;
        this.updatedAt = Instant.now();
    }

    public String getBlock() { return block; }
    public int getTotalScore() { return totalScore; }
    public int getPatientCount() { return patientCount; }
    public double getAverage() { return patientCount == 0 ? 0.0 : (double) totalScore / patientCount; }

    public void applyDelta(int scoreDelta, int patientDelta) {
        this.totalScore += scoreDelta;
        this.patientCount += patientDelta;
        if (this.patientCount < 0) this.patientCount = 0;
        if (this.totalScore < 0) this.totalScore = 0;
        this.updatedAt = Instant.now();
    }
}
