(function () {
  "use strict";

  // Relative paths only. The page is served by the same Spring Boot app that
  // serves the API, so this works identically on localhost:8080, on a NodePort,
  // and behind an ingress — with no rebuild and no hardcoded host.
  const API = {
    flights:  "/api/flights",
    bookings: "/api/bookings",
    auth:     "/api/auth"
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
    dialogBody:   $("dialog-body"),
    authDialog:   $("auth-dialog"),
    authForm:     $("auth-form"),
    authError:    $("auth-error"),
    authTitle:    $("auth-title"),
    authSubmit:   $("auth-submit"),
    authSwitch:   $("auth-switch"),
    nameField:    $("name-field"),
    accountEmail: $("account-email"),
    signIn:       $("sign-in"),
    signOut:      $("sign-out")
  };

  let selectedFlight = null;
  let registering = false;

  // One key per intent to book, minted when the dialog opens rather than per request.
  // That distinction is the whole feature: if the server commits a booking and the
  // response never gets home, the user retries with the same key and the server
  // recognises it. A key minted inside submitBooking would be fresh on every retry.
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

  /** Marks an error as "you need to sign in", so callers can react rather than just report. */
  class NotSignedInError extends Error {
    constructor() {
      super("Please sign in to continue.");
    }
  }

  /**
   * Every request goes through here. The API returns a consistent error shape, so the user
   * sees what the server actually said instead of "failed".
   *
   * <p>401 and 403 are handled differently on purpose. 401 means the server doesn't know who
   * we are -- an expired or missing token -- so the stored session is cleared and the sign-in
   * screen appears. 403 means it knows exactly who we are and this isn't ours; signing in
   * again would achieve nothing, so the message is shown as-is.
   */
  async function request(url, options = {}) {
    const config = {
      ...options,
      headers: { ...(options.headers || {}), ...Auth.authHeader() }
    };

    let response;
    try {
      response = await fetch(url, config);
    } catch (networkError) {
      throw new Error("Could not reach the server. Is the application running?");
    }

    if (response.status === 401) {
      Auth.clear();
      renderAccount();
      throw new NotSignedInError();
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

  function showSignInPrompt(target, title, detail) {
    target.innerHTML =
      '<div class="prompt">' +
        "<strong>" + escapeHtml(title) + "</strong>" +
        escapeHtml(detail) +
        '<div><button class="btn" id="prompt-sign-in">Sign in</button></div>' +
      "</div>";
    $("prompt-sign-in").addEventListener("click", () => openAuth(false));
  }

  function showSkeletons(target, n) {
    target.innerHTML = Array.from({ length: n }, () => '<div class="skeleton"></div>').join("");
  }

  // ---------- account ----------

  function renderAccount() {
    const signedIn = Auth.isSignedIn();
    el.accountEmail.textContent = signedIn ? Auth.email() : "";
    el.signOut.classList.toggle("hidden", !signedIn);
    el.signIn.classList.toggle("hidden", signedIn);
  }

  function openAuth(asRegistration) {
    registering = asRegistration;
    el.authError.classList.add("hidden");
    el.authTitle.textContent = registering ? "Create an account" : "Sign in";
    el.authSubmit.textContent = registering ? "Create account" : "Sign in";
    el.authSwitch.textContent = registering ? "I already have an account" : "Create an account";
    el.nameField.classList.toggle("hidden", !registering);
    $("a-name").required = registering;
    $("a-password").autocomplete = registering ? "new-password" : "current-password";
    el.authDialog.showModal();
  }

  async function submitAuth(event) {
    event.preventDefault();

    const form = event.target;
    if (!form.checkValidity()) { form.reportValidity(); return; }

    el.authSubmit.disabled = true;
    el.authError.classList.add("hidden");

    const payload = {
      email:    $("a-email").value.trim(),
      password: $("a-password").value
    };
    if (registering) payload.fullName = $("a-name").value.trim();

    try {
      const session = await request(API.auth + (registering ? "/register" : "/login"), {
        method:  "POST",
        headers: { "Content-Type": "application/json" },
        body:    JSON.stringify(payload)
      });

      Auth.save(session);
      renderAccount();
      el.authDialog.close();
      form.reset();
      refreshCurrentView();
    } catch (error) {
      el.authError.textContent = error.message;
      el.authError.classList.remove("hidden");
    } finally {
      el.authSubmit.disabled = false;
    }
  }

  function signOut() {
    // Nothing to tell the server: the token is stateless and simply stops being sent.
    // Revoking it before expiry would need server-side state, which is the trade a refresh
    // token exists to make.
    Auth.clear();
    renderAccount();
    el.bookings.innerHTML = "";
    switchTab("search");
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
        if (!flight) return;

        // Asked here rather than after the form is filled in. Making someone type their
        // details and then telling them to sign in wastes the work they just did.
        if (!Auth.isSignedIn()) {
          openAuth(false);
          return;
        }
        openDialog(flight);
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

    // The passenger is usually the person booking, so prefill and let them change it.
    $("p-email").value = Auth.email() || "";

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
      if (error instanceof NotSignedInError) {
        // The token expired while the form was open. Nothing was booked, so send them to
        // sign in and let them try again with the same key.
        el.dialog.close();
        openAuth(false);
        return;
      }
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
        "<p>Quote it at check-in, or find it again under My bookings.</p>" +
        '<div class="ref-big">' + escapeHtml(booking.reference) + "</div>" +
        '<div class="dialog-actions" style="justify-content:center">' +
          '<button type="button" class="btn" id="done-btn">Done</button>' +
        "</div>" +
      "</div>";
    $("done-btn").addEventListener("click", () => el.dialog.close());
  }

  // ---------- my bookings ----------

  function bookingRow(booking) {
    const status = booking.status;
    const settled = status === "CANCELLED" || status === "EXPIRED";

    return '' +
      '<div class="row">' +
        '<div class="row-main">' +
          '<span class="ref">' + escapeHtml(booking.reference) + "</span>" +
          '<span class="pill ' + (settled ? "cancelled" : "confirmed") + '">' +
            escapeHtml(status) + "</span>" +
          '<span class="row-meta">' +
            escapeHtml(booking.flightNumber) + " · " +
            escapeHtml(booking.origin) + " → " + escapeHtml(booking.destination) + " · " +
            booking.seatsBooked + (booking.seatsBooked === 1 ? " seat · " : " seats · ") +
            rupees.format(booking.totalAmount) +
          "</span>" +
        "</div>" +
        (settled ? "" :
          '<button class="btn btn-sm btn-danger" data-cancel="' + booking.id + '">Cancel booking</button>') +
      "</div>";
  }

  /**
   * Asks the server for this user's bookings.
   *
   * <p>Replaces the old lookup by email, which was itself the vulnerability: anyone could
   * type any address and read that person's bookings. The identity now comes from the token,
   * so there is no parameter to tamper with.
   */
  async function loadBookings() {
    if (!Auth.isSignedIn()) {
      showSignInPrompt(el.bookings, "Sign in to see your bookings",
        "Your bookings are tied to your account, not to an email address you type in.");
      return;
    }

    showSkeletons(el.bookings, 2);
    try {
      const bookings = await request(API.bookings + "/me");
      if (!bookings.length) {
        showState(el.bookings, "Nothing booked yet", "Find a flight and your bookings will appear here.");
        return;
      }
      el.bookings.innerHTML = bookings.map(bookingRow).join("");
      el.bookings.querySelectorAll("[data-cancel]").forEach((button) => {
        button.addEventListener("click", () => cancelBooking(button));
      });
    } catch (error) {
      if (error instanceof NotSignedInError) {
        showSignInPrompt(el.bookings, "Your session expired", "Sign in again to see your bookings.");
        return;
      }
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
      if (error instanceof NotSignedInError) {
        showSignInPrompt(el.bookings, "Your session expired", "Sign in again to manage your bookings.");
        return;
      }
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

    if (!searching) loadBookings();
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
  el.bookForm.addEventListener("submit", submitBooking);
  el.authForm.addEventListener("submit", submitAuth);
  el.authSwitch.addEventListener("click", () => openAuth(!registering));
  el.signIn.addEventListener("click", () => openAuth(false));
  el.signOut.addEventListener("click", signOut);

  renderAccount();
  loadAllFlights();
})();
