package ca.uhn.fhir.jpa.starter.common;

import java.util.Date;

import jakarta.persistence.*;

@Entity
@Table(name = "PATIENT_BLOCK")
public class PatientBlock {
    @Id
    @Column(name = "PATIENT_ID", length = 64)
    private String patientId;

    @Column(name = "BLOCK", length = 128)
    private String block;

    @Column(name = "LAST_SCORE")
    private Integer lastScore;

    @Column(name = "last_updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastUpdated;

    public PatientBlock() {}
    public PatientBlock(String patientId, String block, Integer lastScore) {
        this.patientId = patientId;
        this.block = block;
        this.lastScore = lastScore;
    }

    public String getPatientId() { return patientId; }
    public String getBlock() { return block; }
    public Integer getLastScore() { return lastScore; }
    public Date getLastUpdated() { return lastUpdated; }

    public void update(String block, Integer lastScore) {
        this.block = block;
        this.lastScore = lastScore;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
