package ca.uhn.fhir.jpa.starter.common;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class BlockKey implements Serializable {
    private String region;
    private String city;
    private String block;

    public BlockKey() {}
    public BlockKey(String region, String city, String block) {
        this.region = region;
        this.city = city;
        this.block = block;
    }
    public String getRegion() { return region; }
    public String getCity() { return city; }
    public String getBlock() { return block; }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockKey)) return false;
        BlockKey k = (BlockKey) o;
        return Objects.equals(region,k.region) && Objects.equals(city,k.city) && Objects.equals(block,k.block);
    }
    @Override
    public int hashCode() {
        return Objects.hash(region,city,block);
    }
}
