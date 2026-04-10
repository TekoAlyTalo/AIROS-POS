package com.airos.pos.app

import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffRole
import com.airos.pos.core.model.SyncState
import com.airos.pos.core.model.TablePosition
import com.airos.pos.core.model.TableStatus
import com.airos.pos.core.model.Ticket
import com.airos.pos.core.model.TicketLine
import com.airos.pos.core.model.TicketStatus
import com.airos.pos.core.model.RestaurantTable

object SampleData {
    fun localAuthStaffRecords(): List<StaffAuthRecord> = listOf(
        // Local-only staff seed data for Android auth foundation until the POS auth contract is finalized.
        StaffAuthRecord(
            staffId = "staff-1",
            displayName = "Aino Korhonen",
            role = StaffRole.SERVER,
            pin = "2480",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#6E96AA",
        ),
        StaffAuthRecord(
            staffId = "staff-2",
            displayName = "Lauri Niemi",
            role = StaffRole.SERVER,
            pin = "2580",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#7F8FA6",
        ),
        StaffAuthRecord(
            staffId = "staff-3",
            displayName = "Salla Virtanen",
            role = StaffRole.MANAGER,
            pin = "9901",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#7CA4B8",
        ),
        StaffAuthRecord(
            staffId = "staff-4",
            displayName = "Oona Lehtinen",
            role = StaffRole.ADMIN,
            pin = "2468",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#93A6B7",
        ),
    )

    fun menuItems(): List<MenuItem> = listOf(
        MenuItem("item-1", "PIZ001", "Margherita", "Pizzas", 1290, 14.0, barcode = "641000000001"),
        MenuItem("item-2", "PIZ002", "Pepperoni", "Pizzas", 1490, 14.0, barcode = "641000000002"),
        MenuItem("item-3", "DRK001", "Cola 0.33", "Drinks", 390, 14.0, barcode = "641000000003"),
        MenuItem("item-4", "DRK002", "Coffee", "Hot drinks", 320, 14.0, barcode = "641000000004"),
        MenuItem("item-5", "ALC001", "House Lager", "Bar", 780, 25.0, barcode = "641000000005"),
        MenuItem("item-6", "DES001", "Gelato", "Desserts", 560, 14.0, barcode = "641000000006"),
        MenuItem("item-7", "ADD001", "Garlic Dip", "Sides", 180, 14.0, barcode = "641000000007"),
        MenuItem("item-8", "MGR001", "Manual Discount", "Management", -200, 0.0, requiresManagerOverride = true),
        MenuItem("item-9", "ALC002", "Sandell's 4.7%, 0,33l pullo", "Bar", 890, 25.0, barcode = "641000000009"),
        MenuItem("item-10", "ALC003", "Sandell's 4.7%, 0,5l tolkki", "Bar", 990, 25.0, barcode = "641000000010"),
        MenuItem("item-11", "ALC004", "Bacardi Breezer Lime, 0,275l", "Bar", 920, 25.0, barcode = "641000000011"),
        MenuItem("item-12", "ALC005", "Bacardi Breezer Watermelon, 0,275l", "Bar", 920, 25.0, barcode = "641000000012"),
        MenuItem("item-13", "ALC006", "Brooklyn Pilsner, 0,33l bottle", "Bar", 940, 25.0, barcode = "641000000013"),
        MenuItem("item-14", "DRK003", "Lonkero ananas, 0,33l tolkki", "Drinks", 520, 14.0, barcode = "641000000014"),
        MenuItem("item-15", "DRK004", "Battery Sugar Free, 0,33l tolkki", "Drinks", 460, 14.0, barcode = "641000000015"),
        MenuItem("item-16", "DRK005", "Cola Zero 0,5l PET", "Drinks", 450, 14.0, barcode = "641000000016"),
        MenuItem("item-17", "HOT001", "Cappuccino double shot, large", "Hot drinks", 490, 14.0, barcode = "641000000017"),
        MenuItem("item-18", "HOT002", "Latte vanilja, kauramaito", "Hot drinks", 520, 14.0, barcode = "641000000018"),
        MenuItem("item-19", "DES002", "Gelato pistaasi, kaksi palloa", "Desserts", 690, 14.0, barcode = "641000000019"),
        MenuItem("item-20", "DES003", "Cornetto mansikka suklaakastikkeella", "Desserts", 610, 14.0, barcode = "641000000020"),
        MenuItem("item-21", "ADD002", "Valkosipulimajoneesi, talon resepti", "Sides", 220, 14.0, barcode = "641000000021"),
        MenuItem("item-22", "ADD003", "Bataattiranskalaiset, iso annos", "Sides", 520, 14.0, barcode = "641000000022"),
        MenuItem("item-23", "ADD004", "Kana Caesar salaatti, iso annos", "Sides", 1290, 14.0, barcode = "641000000023"),
        MenuItem("item-24", "PIZ003", "Pepperoni Feast, family size", "Pizzas", 1890, 14.0, barcode = "641000000024"),
        MenuItem("item-25", "PIZ004", "Margherita burrata, extra basil", "Pizzas", 1690, 14.0, barcode = "641000000025"),
        MenuItem("item-26", "PIZ005", "Brooklyn BBQ Chicken, 30 cm", "Pizzas", 1790, 14.0, barcode = "641000000026"),
        MenuItem("item-27", "ALC007", "Campari Spritz, 0,2l ready to serve", "Bar", 980, 25.0, barcode = "641000000027"),
        MenuItem("item-28", "ALC008", "Captain Morgan & Cola, 0,33l tolkki", "Bar", 970, 25.0, barcode = "641000000028"),
        MenuItem("item-29", "ALC009", "Bacardi Breezer Passion Mango, 0,275l", "Bar", 920, 25.0, barcode = "641000000029"),
        MenuItem("item-30", "ALC010", "Bacardi Breezer Strawberry, 0,275l", "Bar", 920, 25.0, barcode = "641000000030"),
        MenuItem("item-31", "ADD005", "Beer battered mozzarella sticks, dip", "Sides", 690, 14.0, barcode = "641000000031"),
        MenuItem("item-32", "ADD006", "Bataatti snack bowl, talon aioli", "Sides", 590, 14.0, barcode = "641000000032"),
        MenuItem("item-33", "ALC011", "Bacardi Carta Blanca, 4 cl", "Bar", 960, 25.0, barcode = "641000000033"),
        MenuItem("item-34", "ALC012", "House Red Wine, 16 cl glass", "Bar", 890, 25.0, barcode = "641000000034"),
        MenuItem("item-35", "ALC013", "House White Wine, 16 cl glass", "Bar", 890, 25.0, barcode = "641000000035"),
        MenuItem("item-36", "ALC014", "Campari Soda, 0,2l bottle serve", "Bar", 960, 25.0, barcode = "641000000036"),
        MenuItem("item-37", "ALC015", "Captain Morgan Black, 4 cl", "Bar", 980, 25.0, barcode = "641000000037"),
        MenuItem("item-38", "ALC016", "Brooklyn Summer Ale, 0,33l bottle", "Bar", 950, 25.0, barcode = "641000000038"),
        MenuItem("item-39", "DES004", "Chocolate brownie sundae, warm brownie", "Desserts", 720, 14.0, barcode = "641000000039"),
        MenuItem("item-40", "DRK006", "Orange soda, 0,5l PET bottle", "Drinks", 450, 14.0, barcode = "641000000040"),
    )

    fun floorMap(): FloorMap = FloorMap(
        id = "main-floor",
        name = "Main dining room",
        tables = listOf(
            // TODO-CONTRACT: Replace local table-to-camera mapping with backend floorplan/table camera assignments.
            RestaurantTable("table-1", "T1", "Window", 2, TableStatus.AVAILABLE, position = TablePosition(96, 96, 150, 110), cameraId = "cam1", cameraLabel = "Cam 1 · Window lane"),
            RestaurantTable("table-2", "T2", "Window", 4, TableStatus.OCCUPIED, guestCount = 2, activeTicketId = "ticket-t2-open", position = TablePosition(300, 88, 160, 112), cameraId = "cam1", cameraLabel = "Cam 1 · Window lane"),
            RestaurantTable("table-3", "T3", "Window", 4, TableStatus.RESERVED, position = TablePosition(516, 96, 160, 112), cameraId = "cam1", cameraLabel = "Cam 1 · Window lane"),
            RestaurantTable("table-4", "T4", "Window", 4, TableStatus.AVAILABLE, position = TablePosition(734, 104, 160, 112), cameraId = "cam1", cameraLabel = "Cam 1 · Window lane"),
            RestaurantTable("table-5", "T5", "Main dining", 2, TableStatus.DIRTY, position = TablePosition(116, 286, 150, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-6", "T6", "Main dining", 6, TableStatus.AVAILABLE, position = TablePosition(340, 262, 140, 140), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-7", "T7", "Main dining", 4, TableStatus.OCCUPIED, guestCount = 3, position = TablePosition(556, 286, 170, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-8", "T8", "Main dining", 4, TableStatus.AVAILABLE, position = TablePosition(784, 286, 160, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-9", "T9", "Dining lane", 2, TableStatus.AVAILABLE, position = TablePosition(108, 500, 150, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-10", "T10", "Dining lane", 4, TableStatus.OCCUPIED, guestCount = 2, position = TablePosition(330, 500, 170, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-11", "T11", "Dining lane", 8, TableStatus.RESERVED, position = TablePosition(570, 474, 150, 150), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-12", "T12", "Dining lane", 4, TableStatus.DIRTY, position = TablePosition(812, 500, 160, 110), cameraId = "cam2", cameraLabel = "Cam 2 · Main dining"),
            RestaurantTable("table-13", "T13", "Terrace", 2, TableStatus.AVAILABLE, position = TablePosition(104, 720, 150, 110), cameraId = "cam3", cameraLabel = "Cam 3 · Terrace"),
            RestaurantTable("table-14", "T14", "Terrace", 4, TableStatus.AVAILABLE, position = TablePosition(322, 720, 150, 110), cameraId = "cam3", cameraLabel = "Cam 3 · Terrace"),
            RestaurantTable("table-15", "T15", "Terrace", 4, TableStatus.OCCUPIED, guestCount = 4, position = TablePosition(544, 720, 170, 110), cameraId = "cam3", cameraLabel = "Cam 3 · Terrace"),
            RestaurantTable("table-16", "T16", "Terrace", 4, TableStatus.AVAILABLE, position = TablePosition(770, 720, 160, 110), cameraId = "cam3", cameraLabel = "Cam 3 · Terrace"),
            RestaurantTable("table-17", "T17", "Lounge", 4, TableStatus.AVAILABLE, position = TablePosition(1020, 560, 150, 110), cameraId = "cam4", cameraLabel = "Cam 4 · Lounge deck"),
            RestaurantTable("table-18", "T18", "Lounge", 6, TableStatus.DIRTY, position = TablePosition(1060, 760, 150, 150), cameraId = "cam4", cameraLabel = "Cam 4 · Lounge deck"),
            RestaurantTable("table-19", "T19", "Lounge", 4, TableStatus.AVAILABLE, position = TablePosition(1290, 808, 150, 110), cameraId = "cam4", cameraLabel = "Cam 4 · Lounge deck"),
            RestaurantTable("table-20-21", "T20+T21", "Private corner", 8, TableStatus.OCCUPIED, guestCount = 6, position = TablePosition(1200, 560, 240, 120), cameraId = "cam4", cameraLabel = "Cam 4 · Lounge deck"),
        ),
    )

    fun initialTickets(): Map<String, Ticket> = mapOf(
        "ticket-t2-open" to Ticket(
            id = "ticket-t2-open",
            tableId = "table-2",
            openedByStaffId = "staff-1",
            openedAtEpochMillis = 1_700_000_000_000,
            status = TicketStatus.OPEN,
            lines = listOf(
                TicketLine(
                    id = "line-1",
                    menuItemId = "item-1",
                    name = "Margherita",
                    quantity = 1,
                    unitPriceCents = 1290,
                    totalPriceCents = 1290,
                ),
            ),
            subtotalCents = 1290,
            taxCents = 158,
            totalCents = 1290,
            syncState = SyncState.SYNCED,
        ),
    )
}
