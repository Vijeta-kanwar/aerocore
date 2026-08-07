package com.aerocore.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-quotable reference, e.g. AT-7F3K2Q. Unique, shown instead of the raw id. */
    @Column(nullable = false, unique = true, length = 12)
    private String reference;

    /** Real foreign key, not a loose Long. Deleting a booked flight is now impossible. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    @Column(name = "passenger_name", nullable = false, length = 120)
    private String passengerName;

    @Column(name = "passenger_email", nullable = false, length = 160)
    private String passengerEmail;

    @Column(name = "passenger_phone", nullable = false, length = 20)
    private String passengerPhone;

    @Column(name = "seats_booked", nullable = false)
    private int seatsBooked;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    protected Booking() {
        // required by JPA
    }

    public Booking(String reference, Flight flight, String passengerName, String passengerEmail,
                   String passengerPhone, int seatsBooked, BigDecimal totalAmount) {
        this.reference = reference;
        this.flight = flight;
        this.passengerName = passengerName;
        this.passengerEmail = passengerEmail;
        this.passengerPhone = passengerPhone;
        this.seatsBooked = seatsBooked;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.CONFIRMED;
        this.bookedAt = Instant.now();
    }

    public boolean isCancelled() {
        return status == BookingStatus.CANCELLED;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Flight getFlight() {
        return flight;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public String getPassengerEmail() {
        return passengerEmail;
    }

    public String getPassengerPhone() {
        return passengerPhone;
    }

    public int getSeatsBooked() {
        return seatsBooked;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }
}
