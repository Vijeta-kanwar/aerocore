package com.aerocore.model;

import com.aerocore.exception.IllegalBookingTransitionException;
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

    /** Only ever set while PENDING; the database constraint enforces that. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

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

    /**
     * The only door a status change goes through.
     *
     * <p>There is deliberately no setStatus(). If callers could assign the field
     * directly the transition table would be advice rather than a rule, and the
     * first person in a hurry would route around it.
     */
    public void transitionTo(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalBookingTransitionException(reference, status, target);
        }
        this.status = target;

        // Clearing the deadline isn't housekeeping. ck_bookings_hold_expiry rejects a
        // live deadline on any settled row, so forgetting this line fails the commit.
        if (target != BookingStatus.PENDING) {
            this.holdExpiresAt = null;
        }
    }

    public void confirm() {
        transitionTo(BookingStatus.CONFIRMED);
    }

    public void cancel() {
        transitionTo(BookingStatus.CANCELLED);
    }

    public void expire() {
        transitionTo(BookingStatus.EXPIRED);
    }

    /**
     * Whether this booking is still occupying seats on its flight.
     *
     * <p>Replaces isCancelled(), which happened to give the right answer only while
     * CANCELLED was the sole way for a booking to end.
     */
    public boolean holdsSeats() {
        return status.holdsSeats();
    }

    public boolean isHoldExpired(Instant now) {
        return status == BookingStatus.PENDING && holdExpiresAt.isBefore(now);
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

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }
}
