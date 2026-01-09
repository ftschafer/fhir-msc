package ca.uhn.fhir.jpa.starter.common;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A centralized service to manage application-level locks for patient resources.
 * This ensures that any background process wanting to modify a patient can be
 * synchronized, preventing race conditions.
 */
@Component
public class PatientLockingService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Acquires a lock for a specific patient ID.
     *
     * @param patientId The logical ID of the patient (e.g., "p1").
     * @return The ReentrantLock instance for this patient.
     */
    public ReentrantLock getLockForPatient(String patientId) {
        return locks.computeIfAbsent(patientId, k -> new ReentrantLock());
    }
}
