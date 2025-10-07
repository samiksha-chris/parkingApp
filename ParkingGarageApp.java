package parkingApp;
//ParkingGarageApp.java
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
* Parking Garage Management - single-file implementation
* Features:
* - Configure tariff & spots
* - Vehicle entry (issue ticket)
* - Vehicle exit (calculate fee & accept payment)
* - Occupancy snapshot, ticket listing
*/
public class ParkingGarageApp {
 public static void main(String[] args) {
     GarageManager manager = new GarageManager();
     manager.start();
 }
}

/* ---------- Enums ---------- */
enum SpotType { COMPACT, REGULAR, LARGE, MOTORBIKE, HANDICAPPED }
enum VehicleType { CAR, TRUCK, MOTORBIKE, VAN }

/* ---------- Domain Classes ---------- */
class Vehicle {
 private final String plateNumber;
 private final VehicleType vehicleType;

 public Vehicle(String plateNumber, VehicleType vehicleType) {
     this.plateNumber = plateNumber;
     this.vehicleType = vehicleType;
 }
 public String getPlateNumber() { return plateNumber; }
 public VehicleType getVehicleType() { return vehicleType; }

 public SpotType getRequiredSpot() {
     switch (vehicleType) {
         case MOTORBIKE: return SpotType.MOTORBIKE;
         case TRUCK: return SpotType.LARGE;
         case VAN: return SpotType.REGULAR;
         case CAR:
         default: return SpotType.REGULAR; // prefer REGULAR but COMPACT may be used
     }
 }

 @Override
 public String toString() {
     return plateNumber + " (" + vehicleType + ")";
 }
}

class ParkingSpot {
 private final int id;
 private final SpotType type;
 private boolean occupied = false;
 private String currentTicketId = null;

 public ParkingSpot(int id, SpotType type) {
     this.id = id; this.type = type;
 }
 public int getId() { return id; }
 public SpotType getType() { return type; }
 public boolean isAvailable() { return !occupied; }

 public boolean assignTicket(String ticketId) {
     if (occupied) return false;
     occupied = true;
     currentTicketId = ticketId;
     return true;
 }
 public void release() {
     occupied = false;
     currentTicketId = null;
 }
 public String getCurrentTicketId() { return currentTicketId; }

 @Override
 public String toString() {
     return String.format("Spot[%d:%s] %s", id, type, occupied ? "(OCCUPIED)" : "(FREE)");
 }
}

class Ticket {
 private final String ticketId;
 private final Vehicle vehicle;
 private final int spotId;
 private final LocalDateTime entryTime;
 private LocalDateTime exitTime = null;
 private double fee = 0.0;

 public Ticket(String ticketId, Vehicle vehicle, int spotId, LocalDateTime entryTime) {
     this.ticketId = ticketId;
     this.vehicle = vehicle;
     this.spotId = spotId;
     this.entryTime = entryTime;
 }
 public String getTicketId() { return ticketId; }
 public Vehicle getVehicle() { return vehicle; }
 public int getSpotId() { return spotId; }
 public LocalDateTime getEntryTime() { return entryTime; }
 public LocalDateTime getExitTime() { return exitTime; }
 public double getFee() { return fee; }
 public boolean isOpen() { return exitTime == null; }

 public void close(LocalDateTime exitTime, double fee) {
     this.exitTime = exitTime;
     this.fee = fee;
 }

 public long durationMinutesUpTo(LocalDateTime endTime) {
     return Duration.between(entryTime, endTime).toMinutes();
 }

 @Override
 public String toString() {
     DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
     String exitStr = exitTime == null ? "OPEN" : exitTime.format(fmt);
     return String.format("Ticket[%s] %s -> Spot:%d In:%s Out:%s Fee:%.2f",
         ticketId, vehicle, spotId, entryTime.format(fmt), exitStr, fee);
 }
}

class Tariff {
 private final double perHour; // price per hour
 private final int graceMinutes; // free minutes
 public Tariff(double perHour, int graceMinutes) {
     this.perHour = perHour;
     this.graceMinutes = graceMinutes;
 }
 public double calculateFee(long durationMinutes) {
     if (durationMinutes <= graceMinutes) return 0.0;
     double perMinute = perHour / 60.0;
     double raw = durationMinutes * perMinute;
     // round up to 2 decimals
     return Math.ceil(raw * 100.0) / 100.0;
 }
 @Override public String toString() {
     return String.format("Tariff: %.2f per hour, %d min grace", perHour, graceMinutes);
 }
}

class Payment {
 private final String paymentId;
 private final String ticketId;
 private final double amount;
 private final LocalDateTime paidAt;
 private final String method;

 public Payment(String paymentId, String ticketId, double amount, LocalDateTime paidAt, String method) {
     this.paymentId = paymentId; this.ticketId = ticketId; this.amount = amount; this.paidAt = paidAt; this.method = method;
 }
 public String getPaymentId() { return paymentId; }
 public double getAmount() { return amount; }
 public String getTicketId() { return ticketId; }
 @Override public String toString() {
     DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
     return String.format("Payment[%s] Ticket:%s Amount:%.2f At:%s Method:%s", paymentId, ticketId, amount, paidAt.format(fmt), method);
 }
}

class ExitGate {
 private final int gateId;
 public ExitGate(int id) { this.gateId = id; }

 /**
  * Attempt to exit: requires payment >= requiredFee. If successful,
  * releases spot and returns true.
  */
 public boolean attemptExit(String ticketId, Payment payment, GarageManager.GarageState state) {
     Ticket t = state.activeTickets.get(ticketId);
     if (t == null) {
         System.out.println("Invalid or already-closed ticket id.");
         return false;
     }
     LocalDateTime now = LocalDateTime.now();
     long minutes = t.durationMinutesUpTo(now);
     double required = state.tariff.calculateFee(minutes);
     if (payment.getAmount() + 1e-9 < required) {
         System.out.printf("Payment insufficient. Required: %.2f, Provided: %.2f%n", required, payment.getAmount());
         return false;
     }
     // close ticket, mark spot free
     t.close(now, required);
     ParkingSpot s = state.spots.get(t.getSpotId());
     if (s != null) s.release();
     state.completedPayments.put(payment.getPaymentId(), payment);
     // archive ticket
     state.archivedTickets.put(ticketId, t);
     state.activeTickets.remove(ticketId);
     return true;
 }
}

/* ---------- Manager + Console UI ---------- */
class GarageManager {
 final Scanner scanner = new Scanner(System.in);
 final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

 // grouped state object
 static class GarageState {
     final Map<Integer, ParkingSpot> spots = new LinkedHashMap<>();
     final Map<String, Ticket> activeTickets = new HashMap<>();
     final Map<String, Ticket> archivedTickets = new HashMap<>();
     final Map<String, Payment> completedPayments = new HashMap<>();
     Tariff tariff = new Tariff(40.0, 10);
 }
 final GarageState state = new GarageState();
 final ExitGate exitGate = new ExitGate(1);
 private int nextSpotId = 1;

 void start() {
     seedDemo(); // optional demo
     boolean running = true;
     while (running) {
         printMenu();
         String ch = scanner.nextLine().trim();
         switch (ch) {
             case "1": configureTariff(); break;
             case "2": configureSpots(); break;
             case "3": vehicleEntry(); break;
             case "4": vehicleExit(); break;
             case "5": displayOccupancy(); break;
             case "6": displayTickets(); break;
             case "0": running = false; System.out.println("Exiting..."); break;
             default: System.out.println("Invalid choice."); break;
         }
     }
 }

 private void printMenu() {
     System.out.println("\n=== Parking Garage Manager ===");
     System.out.println("1. Configure Tariff");
     System.out.println("2. Configure/Add Spots");
     System.out.println("3. Vehicle Entry (Issue Ticket)");
     System.out.println("4. Vehicle Exit (Calculate Fee & Pay)");
     System.out.println("5. Display Occupancy Snapshot");
     System.out.println("6. Display Tickets (active & archived)");
     System.out.println("0. Exit");
     System.out.print("Choose: ");
 }

 private void configureTariff() {
     System.out.printf("Current: %s%n", state.tariff);
     System.out.print("Enter new rate per hour (or blank to keep): ");
     String s = scanner.nextLine().trim();
     if (!s.isEmpty()) {
         try {
             double r = Double.parseDouble(s);
             System.out.print("Enter grace minutes (int): ");
             int g = Integer.parseInt(scanner.nextLine().trim());
             state.tariff = new Tariff(r, g);
             System.out.println("Tariff updated: " + state.tariff);
         } catch (Exception e) { System.out.println("Invalid input."); }
     } else {
         System.out.println("Tariff unchanged.");
     }
 }

 private void configureSpots() {
     System.out.println("Add spots by type. Example input: REGULAR:10,COMPACT:5,MOTORBIKE:3");
     System.out.print("Enter specification (or 'list' to view): ");
     String s = scanner.nextLine().trim();
     if (s.equalsIgnoreCase("list")) {
         displaySpots();
         return;
     }
     if (s.isEmpty()) { System.out.println("No input."); return; }
     String[] parts = s.split(",");
     for (String part : parts) {
         String[] pair = part.split(":");
         if (pair.length != 2) continue;
         try {
             SpotType t = SpotType.valueOf(pair[0].trim().toUpperCase());
             int count = Integer.parseInt(pair[1].trim());
             for (int i=0;i<count;i++) {
                 ParkingSpot sp = new ParkingSpot(nextSpotId++, t);
                 state.spots.put(sp.getId(), sp);
             }
         } catch (Exception e) {
             System.out.println("Skipping invalid entry: " + part);
         }
     }
     System.out.println("Spots configured.");
     displaySpots();
 }

 private void displaySpots() {
     if (state.spots.isEmpty()) { System.out.println("No spots configured."); return; }
     System.out.println("Spots:");
     for (ParkingSpot sp : state.spots.values()) {
         System.out.println("  " + sp);
     }
 }

 private void vehicleEntry() {
     if (state.spots.isEmpty()) { System.out.println("No spots configured. Add spots first."); return; }
     System.out.print("Enter plate number: ");
     String plate = scanner.nextLine().trim();
     if (plate.isEmpty()) { System.out.println("Plate required."); return; }
     System.out.print("Vehicle type (CAR, TRUCK, MOTORBIKE, VAN): ");
     String vt = scanner.nextLine().trim().toUpperCase();
     VehicleType vtype;
     try { vtype = VehicleType.valueOf(vt); } catch (Exception e) { System.out.println("Invalid type."); return; }
     Vehicle v = new Vehicle(plate, vtype);
     ParkingSpot allocated = findSuitableSpot(v);
     if (allocated == null) {
         System.out.println("No suitable spot available. Entry denied.");
         return;
     }
     String ticketId = UUID.randomUUID().toString().substring(0,8).toUpperCase();
     boolean ok = allocated.assignTicket(ticketId);
     if (!ok) {
         System.out.println("Unexpected allocation error (spot already occupied).");
         return;
     }
     Ticket t = new Ticket(ticketId, v, allocated.getId(), LocalDateTime.now());
     state.activeTickets.put(ticketId, t);
     System.out.println("\n--- Ticket Issued ---");
     System.out.println("Ticket ID: " + t.getTicketId());
     System.out.println("Vehicle  : " + t.getVehicle());
     System.out.println("Spot     : " + allocated);
     System.out.println("Entry at : " + t.getEntryTime().format(dtf));
     System.out.println("---------------------");
 }

 private ParkingSpot findSuitableSpot(Vehicle v) {
     SpotType required = v.getRequiredSpot();
     // Candidate order lists per vehicle requirement (preference)
     List<SpotType> order = new ArrayList<>();
     switch (required) {
         case MOTORBIKE:
             order = Arrays.asList(SpotType.MOTORBIKE, SpotType.COMPACT, SpotType.REGULAR);
             break;
         case LARGE:
             order = Arrays.asList(SpotType.LARGE);
             break;
         case REGULAR:
         default:
             order = Arrays.asList(SpotType.COMPACT, SpotType.REGULAR, SpotType.LARGE);
             break;
     }
     // Search spots in insertion order for the first available matching type
     for (SpotType t : order) {
         for (ParkingSpot sp : state.spots.values()) {
             if (sp.getType() == t && sp.isAvailable()) return sp;
         }
     }
     // As last resort, allow handicapped spot if free (only if vehicle is allowed -- here we assume allowed)
     for (ParkingSpot sp : state.spots.values()) {
         if (sp.getType() == SpotType.HANDICAPPED && sp.isAvailable()) return sp;
     }
     return null;
 }

 private void vehicleExit() {
     if (state.activeTickets.isEmpty()) { System.out.println("No active tickets (no vehicles to exit)."); return; }
     System.out.println("Active Tickets:");
     for (Ticket t : state.activeTickets.values()) {
         System.out.printf("  %s  (Spot %d) Entered: %s%n", t.getTicketId(), t.getSpotId(), t.getEntryTime().format(dtf));
     }
     System.out.print("Enter Ticket ID to process exit: ");
     String tid = scanner.nextLine().trim().toUpperCase();
     Ticket t = state.activeTickets.get(tid);
     if (t == null) { System.out.println("Invalid ticket id."); return; }
     LocalDateTime now = LocalDateTime.now();
     long minutes = t.durationMinutesUpTo(now);
     double fee = state.tariff.calculateFee(minutes);

     System.out.println("\n--- Fee Breakdown ---");
     System.out.println("Ticket : " + t.getTicketId());
     System.out.println("Vehicle: " + t.getVehicle());
     System.out.println("Entry  : " + t.getEntryTime().format(dtf));
     System.out.println("Exit   : " + now.format(dtf));
     System.out.println("Duration (minutes): " + minutes);
     System.out.printf("Tariff: %s%n", state.tariff);
     System.out.printf("Amount due: %.2f%n", fee);
     System.out.println("---------------------");

     // Payment
     System.out.print("Enter payment amount: ");
     String paidStr = scanner.nextLine().trim();
     double paid;
     try {
         paid = Double.parseDouble(paidStr);
     } catch (Exception e) { System.out.println("Invalid amount."); return; }
     System.out.print("Payment method (CASH/CARD/UPI): ");
     String method = scanner.nextLine().trim().toUpperCase();
     String payId = "P-" + UUID.randomUUID().toString().substring(0,7).toUpperCase();
     Payment payment = new Payment(payId, tid, paid, LocalDateTime.now(), method);

     boolean success = exitGate.attemptExit(tid, payment, state);
     if (!success) {
         System.out.println("Exit denied. Payment failed or insufficient.");
         return;
     }
     // produce receipt (including change)
     double change = paid - fee;
     if (change < 0) change = 0.0;
     System.out.println("\n--- Payment Receipt ---");
     System.out.println(payment);
     System.out.printf("Amount due : %.2f%n", fee);
     System.out.printf("Amount paid: %.2f%n", paid);
     System.out.printf("Change     : %.2f%n", change);
     System.out.println("Ticket closed and spot released. Thank you!");
     System.out.println("------------------------");
 }

 private void displayOccupancy() {
     System.out.println("\n=== Occupancy Snapshot ===");
     if (state.spots.isEmpty()) { System.out.println("No spots configured."); return; }
     long free = state.spots.values().stream().filter(ParkingSpot::isAvailable).count();
     long occupied = state.spots.size() - free;
     System.out.printf("Total spots: %d  Occupied: %d  Free: %d%n", state.spots.size(), occupied, free);
     for (ParkingSpot sp : state.spots.values()) {
         String line = String.format("  %s", sp.toString());
         if (!sp.isAvailable()) {
             Ticket t = state.activeTickets.get(sp.getCurrentTicketId());
             if (t != null) line += " -> " + t.getVehicle() + " (Ticket " + t.getTicketId() + ")";
         }
         System.out.println(line);
     }
 }

 private void displayTickets() {
     System.out.println("\n--- Active Tickets ---");
     if (state.activeTickets.isEmpty()) System.out.println("  (none)");
     else for (Ticket t : state.activeTickets.values()) System.out.println("  " + t);
     System.out.println("\n--- Archived Tickets ---");
     if (state.archivedTickets.isEmpty()) System.out.println("  (none)");
     else for (Ticket t : state.archivedTickets.values()) System.out.println("  " + t);
     System.out.println("\n--- Payments ---");
     if (state.completedPayments.isEmpty()) System.out.println("  (none)");
     else for (Payment p : state.completedPayments.values()) System.out.println("  " + p);
 }

 private void seedDemo() {
     // add some spots and demo entries to help testing
     state.spots.put(nextSpotId, new ParkingSpot(nextSpotId++, SpotType.COMPACT));
     state.spots.put(nextSpotId, new ParkingSpot(nextSpotId++, SpotType.REGULAR));
     state.spots.put(nextSpotId, new ParkingSpot(nextSpotId++, SpotType.REGULAR));
     state.spots.put(nextSpotId, new ParkingSpot(nextSpotId++, SpotType.MOTORBIKE));
     state.spots.put(nextSpotId, new ParkingSpot(nextSpotId++, SpotType.LARGE));

     // issue one seed ticket (vehicle already inside)
     Vehicle v = new Vehicle("DL-01-AAA", VehicleType.CAR);
     ParkingSpot sp = findSuitableSpot(v);
     if (sp != null) {
         String tid = "T-" + UUID.randomUUID().toString().substring(0,7).toUpperCase();
         sp.assignTicket(tid);
         // set entry time 90 minutes ago to show computed fee later
         Ticket t = new Ticket(tid, v, sp.getId(), LocalDateTime.now().minusMinutes(90));
         state.activeTickets.put(tid, t);
     }
 }
}
