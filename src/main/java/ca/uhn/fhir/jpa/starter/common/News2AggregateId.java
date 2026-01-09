package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class News2AggregateId implements Serializable {
    private String patientId;
    private String code;

    public News2AggregateId() {}
    public News2AggregateId(String patientId, String code) {
        this.patientId = patientId;
        this.code = code;
    }

    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof News2AggregateId)) return false;
        News2AggregateId that = (News2AggregateId) o;
        return Objects.equals(patientId, that.patientId) && Objects.equals(code, that.code);
    }
    @Override public int hashCode() { return Objects.hash(patientId, code); }
}
