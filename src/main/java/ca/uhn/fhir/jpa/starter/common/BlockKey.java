package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class BlockKey implements Serializable {
    private String neighborhood;
    private String city;
    private String block;

    public BlockKey() {}
    public BlockKey(String city, String neighborhood, String block) {
        this.neighborhood = neighborhood;
        this.city = city;
        this.block = block;
    }
    public String getNeighborhood() { return neighborhood; }
    public String getCity() { return city; }
    public String getBlock() { return block; }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockKey)) return false;
        BlockKey k = (BlockKey) o;
        return Objects.equals(neighborhood,k.neighborhood) && Objects.equals(city,k.city) && Objects.equals(block,k.block);
    }
    @Override
    public int hashCode() {
        return Objects.hash(neighborhood,city,block);
    }
}
