package com.example.jpalab.stock;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "versioned_stocks")
public class VersionedStock {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "versioned_stock_sequence")
    @SequenceGenerator(
            name = "versioned_stock_sequence",
            sequenceName = "versioned_stock_sequence",
            allocationSize = 1
    )
    private Long id;

    private int quantity;

    @Version
    private long version;

    protected VersionedStock() {
    }

    public VersionedStock(int quantity) {
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getVersion() {
        return version;
    }

    public void decrease() {
        if (quantity <= 0) {
            throw new IllegalStateException("stock is empty");
        }

        quantity--;
    }
}
