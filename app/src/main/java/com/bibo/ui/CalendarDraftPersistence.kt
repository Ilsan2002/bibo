package com.bibo.ui

import com.bibo.data.ActivityBlock
import com.bibo.data.DeviceCalendarEvent
import java.time.*

/** Primitive saved-state payload: no Context, provider cursor, or whole entity is retained. */
internal object CalendarDraftPersistence {
    fun encode(request: CalendarEditRequest, draft: CalendarEditorValue?): ArrayList<String> {
        val item = request.editItem
        val block = (item as? CalendarItem.Block)?.block
        val device = (item as? CalendarItem.Device)?.event
        return arrayListOf("2", request.sheetTitle, request.title, request.start?.toString().orEmpty(), request.end?.toString().orEmpty(),
            request.date.toString(), request.source, request.endDate?.toString().orEmpty(), request.allDay.toString(), request.zone.id,
            request.startOffset?.id.orEmpty(), request.endOffset?.id.orEmpty(), request.warning.orEmpty(), request.requiresTimeCorrection.toString(),
            when (item) { is CalendarItem.Block -> "BLOCK"; is CalendarItem.Device -> "DEVICE"; else -> "NONE" },
            (block?.id ?: device?.id)?.toString().orEmpty(), item?.title.orEmpty(), item?.startMillis?.toString().orEmpty(), item?.endMillis?.toString().orEmpty(),
            block?.source.orEmpty(), (block?.allDay ?: device?.allDay ?: false).toString(), block?.eventZone ?: device?.eventZone.orEmpty(),
            device?.calendarId?.toString().orEmpty(), device?.calendarName.orEmpty(), device?.writable.toString(), device?.recurring.toString(),
            (draft != null).toString(), draft?.title.orEmpty(), draft?.start?.toString().orEmpty(), draft?.end?.toString().orEmpty(),
            draft?.allDay.toString(), draft?.zone?.id.orEmpty(), draft?.startOffset?.id.orEmpty(), draft?.endOffset?.id.orEmpty(),
            draft?.calendarId?.toString().orEmpty(), draft?.timeCorrectionAccepted.toString(), request.commandId,
            request.destinationLocked.toString(), request.lockedCalendarId?.toString().orEmpty())
    }
    fun decode(value: List<String>): Pair<CalendarEditRequest, CalendarEditorValue?>? = runCatching {
        if (!((value.size == 37 && value[0] == "1") || (value.size == 39 && value[0] == "2"))) return null
        fun time(index: Int) = value[index].takeIf { it.isNotBlank() }?.let(LocalTime::parse)
        fun offset(index: Int) = value[index].takeIf { it.isNotBlank() }?.let(ZoneOffset::of)
        val item = when (value[14]) {
            "BLOCK" -> CalendarItem.Block(ActivityBlock(value[15].toLong(), value[16], value[17].toLong(), value[18].toLong(), value[19],
                allDay = value[20].toBooleanStrict(), eventZone = value[21].ifBlank { null }))
            "DEVICE" -> CalendarItem.Device(DeviceCalendarEvent(value[15].toLong(), value[16], value[17].toLong(), value[18].toLong(), 0,
                value[20].toBooleanStrict(), value[22].toLong(), value[23], value[24].toBooleanStrict(), value[25].toBooleanStrict(), value[21]))
            "NONE" -> null
            else -> return null
        }
        val request = CalendarEditRequest(value[1], value[2], time(3), time(4), LocalDate.parse(value[5]), value[6],
            value[7].takeIf { it.isNotBlank() }?.let(LocalDate::parse), value[8].toBooleanStrict(), ZoneId.of(value[9]), offset(10), offset(11),
            item, value[12].ifBlank { null }, value[13].toBooleanStrict(), value[36],
            if (value.size == 39) value[37].toBooleanStrict() else false,
            if (value.size == 39) value[38].takeIf { it.isNotBlank() }?.toLong() else null)
        val draft = if (value[26].toBooleanStrict()) CalendarEditorValue(value[27], LocalDateTime.parse(value[28]), LocalDateTime.parse(value[29]),
            value[30].toBooleanStrict(), ZoneId.of(value[31]), offset(32), offset(33), value[34].takeIf { it.isNotBlank() }?.toLong(), value[35].toBooleanStrict()) else null
        request to draft
    }.getOrNull()
}
