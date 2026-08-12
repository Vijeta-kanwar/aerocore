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
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-quotable reference, e.g. AT-7F3K2Q. Doubles as the gateway's idempotency key. */
    @Column(nullable = false, unique = true, length = 12)
    private String reference;

    /** Real foreign key, not a loose Long. Deleting a booked flight is now impossible. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "user_id", nullable = false)
private User user;

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
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "payment_charge_id", length = 64)
    private String paymentChargeId;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    protected Booking() {
        // required by JPA
    }

    /**
     * A booking now starts life as an unpaid hold, not a confirmed seat.
     *
     * <p>Until payment existed, "created" and "confirmed" were the same moment. They aren't
     * any more: the seat is reserved here, but nobody has paid for it, and if nobody does the
     * sweeper takes it back at holdExpiresAt.
     */
    public Booking(String reference, Flight flight, User user, String passengerName, String passengerEmail,
               String passengerPhone, int seatsBooked, BigDecimal totalAmount,
               Duration holdFor)  {
        this.reference = reference;
        this.flight = flight;
        this.user = user;
        this.passengerName = passengerName;
        this.passengerEmail = passengerEmail;
        this.passengerPhone = passengerPhone;
        this.seatsBooked = seatsBooked;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.PENDING;
        this.holdExpiresAt = Instant.now().plus(holdFor);
        this.bookedAt = Instant.now();
    }

    /**
     * The only door a status change goes through.
     *
     * <p>There is deliberately no setStatus(). If callers could assign the field directly the
     * transition table would be advice rather than a rule.
     */
    public void transitionTo(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalBookingTransitionException(reference, status, target);
        }
        this.status = target;

        // Clearing the deadline isn't housekeeping. ck_bookings_hold_expiry rejects a live
        // deadline on a settled row, so forgetting this line fails the commit.
        if (!target.carriesDeadline()) {
            this.holdExpiresAt = null;
        }
    }

    /** Marks the booking as untouchable by the sweeper, just before the gateway is called. */
    public void beginPayment() {
        transitionTo(BookingStatus.PAYMENT_PENDING);
    }

    public void confirm(String chargeId) {
        transitionTo(BookingStatus.CONFIRMED);
        this.paymentChargeId = chargeId;
    }

    public void cancel() {
        transitionTo(BookingStatus.CANCELLED);
    }

    public void expire() {
        transitionTo(BookingStatus.EXPIRED);
    }

    /** Whether this booking is still occupying seats on its flight. */
    public boolean holdsSeats() {
        return status.holdsSeats();
    }

    public boolean isHoldExpired(Instant now) {
        return holdExpiresAt != null && holdExpiresAt.isBefore(now);
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

    public String getPaymentChargeId() {
        return paymentChargeId;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public User getUser() {
    return user;
    }
    
}
