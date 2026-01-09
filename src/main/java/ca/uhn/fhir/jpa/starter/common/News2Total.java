package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "news2_total")
public class News2Total {
    @Id
    private String patientId;
    private int total;

    public News2Total() {}
    public News2Total(String patientId, int total) { this.patientId = patientId; this.total = total; }

    public String getPatientId() { return patientId; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
}
