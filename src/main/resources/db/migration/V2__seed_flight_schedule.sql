-- Demo schedule so a fresh clone has something to search immediately.
-- ON CONFLICT keeps this safe to re-run against an existing volume.

INSERT INTO flights (flight_number, airline, origin, destination, departure_time, arrival_time, price, total_seats, available_seats)
VALUES
    ('AI101', 'Air India',  'Delhi',     'Mumbai',    '06:00', '08:15', 5499.00, 180, 180),
    ('6E204', 'IndiGo',     'Delhi',     'Mumbai',    '09:30', '11:40', 4899.00, 186, 186),
    ('UK811', 'Vistara',    'Delhi',     'Bengaluru', '07:15', '10:00', 6250.00, 158, 158),
    ('6E512', 'IndiGo',     'Delhi',     'Bengaluru', '18:45', '21:30', 5875.00, 186, 186),
    ('AI503', 'Air India',  'Mumbai',    'Delhi',     '12:00', '14:10', 5320.00, 180, 180),
    ('SG703', 'SpiceJet',   'Mumbai',    'Goa',       '15:20', '16:30', 3150.00, 189, 189),
    ('UK927', 'Vistara',    'Bengaluru', 'Delhi',     '20:10', '23:00', 6480.00, 158, 158),
    ('6E339', 'IndiGo',     'Chennai',   'Kolkata',   '11:05', '13:25', 4720.00, 186, 186),
    ('AI677', 'Air India',  'Kolkata',   'Delhi',     '17:40', '19:55', 5990.00, 180, 180),
    ('SG118', 'SpiceJet',   'Delhi',     'Jaipur',    '08:00', '09:00', 2450.00, 189, 189)
ON CONFLICT (flight_number) DO NOTHING;
