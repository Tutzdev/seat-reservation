package dev.tutz.reservation.event;

public record SeatResponse(long id, String section, String row, int number, long priceCents, SeatStatus status) {

    static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getSection(), seat.getRowLabel(), seat.getNumber(),
                seat.getPriceCents(), seat.getStatus());
    }
}
