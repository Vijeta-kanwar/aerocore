(function () {
  "use strict";

  // Relative paths only. The page is served by the same Spring Boot app that
  // serves the API, so this works identically on localhost:8080, on a NodePort,
  // and behind an ingress — with no rebuild and no hardcoded host.
  const API = {
    flights:  "/api/flights",
    bookings: "/api/bookings"
  };

  const IATA = {
    delhi: "DEL", mumbai: "BOM", bengaluru: "BLR", bangalore: "BLR",
    goa: "GOI", chennai: "MAA", kolkata: "CCU", jaipur: "JAI",
    hyderabad: "HYD", pune: "PNQ", ahmedabad: "AMD", kochi: "COK"
  };

  const rupees = new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", maximumFractionDigits: 0
  });

  const $ = (id) => document.getElementById(id);

  const el = {
    results:      $("results"),
    resultsTitle: $("results-title"),
    resultsCount: $("results-count"),
    bookings:     $("bookings"),
    searchForm:   $("search-form"),
    origin:       $("origin"),
    destination:  $("destination"),
    cities:       $("cities"),
    dialog:       $("book-dialog"),
    bookForm:     $("book-form"),
    formError:    $("form-error"),
    dialogSub:    $("dialog-sub"),
    dialogTitle:  $("dialog-title"),
    dialogBody:   $("dialog-body")
  };

  let selectedFlight = null;

  // One key per intent to book, minted when the dialog opens rather than per
  // request. That distinction is the whole feature: if the server commits a
  // booking and the response never gets home, the user retries the same key and
  // the server recognises it. A key minted inside submitBooking would be fresh
  // on every retry and the server would happily book twice.
  let bookingKey = null;

  const dialogTemplate = el.dialogBody.innerHTML;

  // ---------- helpers ----------

  function iata(city) {
    if (!city) return "???";
    return IATA[city.trim().toLowerCase()] || city.trim().slice(0, 3).toUpperCase();
  }

  function duration(dep, arr) {
    const [dh, dm] = dep.split(":").map(Number);
    const [ah, am] = arr.split(":").map(Number);
    let mins = (ah * 60 + am) - (dh * 60 + dm);
    if (mins < 0) mins += 24 * 60;           // overnight leg
    return Math.floor(mins / 60) + "h " + String(mins % 60).padStart(2, "0") + "m";
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (c) => (
      { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
    ));
  }

  /**
   * Every failure path goes through here. The API returns a consistent error
   * shape, so the user sees what the server actually said instead of "failed".
   */
  async function request(url, options) {
    let response;
    try {
      response = await fetch(url, options);
    } catch (networkError) {
      throw new Error("Could not reach the server. Is the application running?");
    }

    if (response.status === 204) return null;

    const body = await response.json().catch(() => null);

    if (!response.ok) {
      if (body && body.fieldErrors) {
        throw new Error(Object.values(body.fieldErrors).join(". "));
      }
      throw new Error((body && body.message) || ("Request failed with status " + response.status));
    }
    return body;
  }

  function showState(target, title, detail, isError) {
    target.innerHTML =
      '<div class="state' + (isError ? " error" : "") + '">' +
        "<strong>" + escapeHtml(title) + "</strong>" +
        escapeHtml(detail) +
      "</div>";
  }

  function showSkeletons(target, n) {
    target.innerHTML = Array.from({ length: n }, () => '<div class="skeleton"></div>').join("");
  }

  // ---------- flights ----------

  function flightCard(flight) {
    const low = flight.availableSeats > 0 && flight.availableSeats <= 10;
    const soldOut = flight.availableSeats === 0;

    return '' +
      '<article class="pass">' +
        '<div class="pass-body">' +
          '<div class="pass-top">' +
            '<span class="code">' + escapeHtml(flight.flightNumber) + "</span>" +
            '<span class="airline">' + escapeHtml(flight.airline) + "</span>" +
          "</div>" +
          '<div class="leg">' +
            '<div class="port">' +
              '<div class="iata">' + iata(flight.origin) + "</div>" +
              '<div class="time">' + escapeHtml(flight.departureTime) + "</div>" +
              '<div class="city">' + escapeHtml(flight.origin) + "</div>" +
            "</div>" +
            '<div class="arc">' +
              '<div class="dur">' + duration(flight.departureTime, flight.arrivalTime) + "</div>" +
              '<div class="track"></div>' +
            "</div>" +
            '<div class="port">' +
              '<div class="iata">' + iata(flight.destination) + "</div>" +
              '<div class="time">' + escapeHtml(flight.arrivalTime) + "</div>" +
              '<div class="city">' + escapeHtml(flight.destination) + "</div>" +
            "</div>" +
          "</div>" +
        "</div>" +
        '<div class="stub">' +
          '<div class="fare">' + rupees.format(flight.price) + "</div>" +
          '<div class="fare-note">per seat</div>' +
          '<button class="btn btn-sm" data-book="' + flight.id + '"' + (soldOut ? " disabled" : "") + ">" +
            (soldOut ? "Sold out" : "Book") +
          "</button>" +
          '<div class="seats' + (low ? " low" : "") + '">' +
            (soldOut ? "no seats left" : flight.availableSeats + " seats left") +
          "</div>" +
        "</div>" +
      "</article>";
  }

  function renderFlights(flights) {
    el.resultsCount.textContent = flights.length + (flights.length === 1 ? " flight" : " flights");

    if (!flights.length) {
      showState(el.results, "No flights on this route",
        "Try Delhi to Mumbai, or clear the fields to see the whole schedule.");
      return;
    }

    el.results.innerHTML = flights.map(flightCard).join("");
    el.results.querySelectorAll("[data-book]").forEach((button) => {
      button.addEventListener("click", () => {
        const flight = flights.find((f) => String(f.id) === button.dataset.book);
        if (flight) openDialog(flight);
      });
    });
  }

  async function loadAllFlights() {
    el.resultsTitle.textContent = "Every scheduled flight";
    el.resultsCount.textContent = "";
    showSkeletons(el.results, 4);
    try {
      const flights = await request(API.flights);
      renderFlights(flights);
      populateCities(flights);
    } catch (error) {
      showState(el.results, "Could not load the schedule", error.message, true);
    }
  }

  async function searchFlights(origin, destination) {
    el.resultsTitle.textContent = origin + " to " + destination;
    el.resultsCount.textContent = "";
    showSkeletons(el.results, 3);
    const query = "?origin=" + encodeURIComponent(origin) + "&destination=" + encodeURIComponent(destination);
    try {
      renderFlights(await request(API.flights + "/search" + query));
    } catch (error) {
      showState(el.results, "Search failed", error.message, true);
    }
  }

  function populateCities(flights) {
    const set = new Set();
    flights.forEach((f) => { set.add(f.origin); set.add(f.destination); });
    el.cities.innerHTML = [...set].sort()
      .map((c) => '<option value="' + escapeHtml(c) + '">').join("");
  }

  // ---------- booking dialog ----------

  function openDialog(flight) {
    selectedFlight = flight;
    bookingKey = crypto.randomUUID();
    el.dialogBody.innerHTML = dialogTemplate;
    rebindDialog();

    el.dialogTitle.textContent = "Book " + flight.flightNumber;
    el.dialogSub.textContent =
      flight.origin + " → " + flight.destination + " · " + flight.departureTime + " · " + flight.airline;

    const max = Math.min(flight.availableSeats, 9);
    const seats = $("p-seats");
    seats.innerHTML = Array.from({ length: max }, (_, i) =>
      "<option value=" + (i + 1) + ">" + (i + 1) + (i ? " seats" : " seat") + "</option>").join("");

    seats.addEventListener("change", updateTotal);
    updateTotal();
    el.dialog.showModal();
  }

  function updateTotal() {
    const seats = Number($("p-seats").value || 1);
    $("p-total").textContent = rupees.format(selectedFlight.price * seats);
  }

  function rebindDialog() {
    el.formError = $("form-error");
    $("cancel-dialog").addEventListener("click", () => el.dialog.close());
  }

  async function submitBooking(event) {
    event.preventDefault();

    const form = event.target;
    if (!form.checkValidity()) { form.reportValidity(); return; }

    const button = $("confirm-btn");
    button.disabled = true;
    button.textContent = "Booking…";
    el.formError.classList.add("hidden");

    const payload = {
      flightId:       selectedFlight.id,
      passengerName:  $("p-name").value.trim(),
      passengerEmail: $("p-email").value.trim(),
      passengerPhone: $("p-phone").value.trim(),
      seatsBooked:    Number($("p-seats").value)
    };

    try {
      const booking = await request(API.bookings, {
        method:  "POST",
        headers: {
          "Content-Type":    "application/json",
          // Deliberately the same value on a retry — see bookingKey above.
          "Idempotency-Key": bookingKey
        },
        body:    JSON.stringify(payload)
      });
      showConfirmation(booking);
      refreshCurrentView();
    } catch (error) {
      el.formError.textContent = error.message;
      el.formError.classList.remove("hidden");
      button.disabled = false;
      button.textContent = "Confirm booking";
    }
  }

  function showConfirmation(booking) {
    el.dialogTitle.textContent = "Seat reserved";
    el.dialogSub.textContent = booking.flightNumber + " · " + booking.origin + " → " + booking.destination;
    el.dialogBody.innerHTML =
      '<div class="confirm">' +
        '<div class="tick">✓</div>' +
        "<h4>Keep this reference</h4>" +
        "<p>Quote it at check-in, or use your email to find this booking again.</p>" +
        '<div class="ref-big">' + escapeHtml(booking.reference) + "</div>" +
        '<div class="dialog-actions" style="justify-content:center">' +
          '<button type="button" class="btn" id="done-btn">Done</button>' +
        "</div>" +
      "</div>";
    $("done-btn").addEventListener("click", () => el.dialog.close());
  }

  // ---------- my bookings ----------

  function bookingRow(booking) {
    const cancelled = booking.status === "CANCELLED";
    return '' +
      '<div class="row">' +
        '<div class="row-main">' +
          '<span class="ref">' + escapeHtml(booking.reference) + "</span>" +
          '<span class="pill ' + (cancelled ? "cancelled" : "confirmed") + '">' +
            escapeHtml(booking.status) + "</span>" +
          '<span class="row-meta">' +
            escapeHtml(booking.flightNumber) + " · " +
            escapeHtml(booking.origin) + " → " + escapeHtml(booking.destination) + " · " +
            booking.seatsBooked + (booking.seatsBooked === 1 ? " seat · " : " seats · ") +
            rupees.format(booking.totalAmount) +
          "</span>" +
        "</div>" +
        (cancelled ? "" :
          '<button class="btn btn-sm btn-danger" data-cancel="' + booking.id + '">Cancel booking</button>') +
      "</div>";
  }

  async function loadBookings() {
    const email = $("lookup-email").value.trim();
    if (!email) {
      showState(el.bookings, "Enter your email", "Bookings are found using the email you booked with.");
      return;
    }

    showSkeletons(el.bookings, 2);
    try {
      const bookings = await request(API.bookings + "/passenger?email=" + encodeURIComponent(email));
      if (!bookings.length) {
        showState(el.bookings, "Nothing booked with this email", "Check the spelling, or book a flight first.");
        return;
      }
      el.bookings.innerHTML = bookings.map(bookingRow).join("");
      el.bookings.querySelectorAll("[data-cancel]").forEach((button) => {
        button.addEventListener("click", () => cancelBooking(button));
      });
    } catch (error) {
      showState(el.bookings, "Could not load bookings", error.message, true);
    }
  }

  async function cancelBooking(button) {
    button.disabled = true;
    button.textContent = "Cancelling…";
    try {
      await request(API.bookings + "/" + button.dataset.cancel + "/cancel", { method: "POST" });
      loadBookings();
    } catch (error) {
      button.disabled = false;
      button.textContent = "Cancel booking";
      showState(el.bookings, "Could not cancel", error.message, true);
    }
  }

  // ---------- tabs ----------

  function switchTab(name) {
    const searching = name === "search";
    $("tab-search").setAttribute("aria-selected", String(searching));
    $("tab-bookings").setAttribute("aria-selected", String(!searching));
    $("panel-search").classList.toggle("hidden", !searching);
    $("panel-bookings").classList.toggle("hidden", searching);
    $("rail").classList.toggle("hidden", !searching);

    if (!searching && !el.bookings.innerHTML) {
      showState(el.bookings, "Enter your email", "Bookings are found using the email you booked with.");
    }
  }

  function refreshCurrentView() {
    if ($("panel-search").classList.contains("hidden")) loadBookings();
    else if (el.origin.value.trim() && el.destination.value.trim()) {
      searchFlights(el.origin.value.trim(), el.destination.value.trim());
    } else loadAllFlights();
  }

  // ---------- wiring ----------

  el.searchForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const origin = el.origin.value.trim();
    const destination = el.destination.value.trim();
    if (origin && destination) searchFlights(origin, destination);
    else loadAllFlights();
  });

  $("swap").addEventListener("click", () => {
    const held = el.origin.value;
    el.origin.value = el.destination.value;
    el.destination.value = held;
  });

  $("tab-search").addEventListener("click", () => switchTab("search"));
  $("tab-bookings").addEventListener("click", () => switchTab("bookings"));
  $("lookup-btn").addEventListener("click", loadBookings);
  $("lookup-email").addEventListener("keydown", (e) => { if (e.key === "Enter") loadBookings(); });
  el.bookForm.addEventListener("submit", submitBooking);

  loadAllFlights();
})();
