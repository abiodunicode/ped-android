package com.ped.mobile.data

object CaptureSchemas {
    val all: Map<CaptureKind, CaptureSchema> = mapOf(
        CaptureKind.PED to CaptureSchema(
            label = "On Premise",
            endpoint = "/ped-save",
            titleKey = "chassis",
            description = "Record the vehicle and its condition. Save your photos on this device, ready to upload.",
            textFields = listOf(
                TextFieldSpec(
                    key = "chassis",
                    label = "Chassis number",
                    required = true,
                    placeholder = "Enter chassis number"
                )
            ),
            imageFields = listOf(
                ImageFieldSpec("driver_face", "Driver face", required = true),
                ImageFieldSpec("dashboard", "Dashboard"),
                ImageFieldSpec("vkey", "Vehicle key"),
                ImageFieldSpec("backview", "Vehicle back view"),
                ImageFieldSpec("frontview", "Vehicle front view"),
                ImageFieldSpec("chassis_capture", "Chassis capture"),
                ImageFieldSpec("extra_pic1", "Extra picture 1"),
                ImageFieldSpec("extra_pic2", "Extra picture 2"),
                ImageFieldSpec("extra_pic3", "Extra picture 3"),
                ImageFieldSpec("extra_pic4", "Extra picture 4")
            )
        ),
        CaptureKind.OFFDOCK to CaptureSchema(
            label = "Offdock",
            endpoint = "/ped-save-offdock",
            titleKey = "chassis",
            description = "Capture vehicles at the offdock facility. Your records stay on this device until you upload them.",
            textFields = listOf(
                TextFieldSpec(
                    key = "chassis",
                    label = "Chassis number",
                    required = true,
                    placeholder = "Enter chassis number"
                )
            ),
            imageFields = listOf(
                ImageFieldSpec("driver_face", "Driver face", required = true),
                ImageFieldSpec("dashboard", "Dashboard"),
                ImageFieldSpec("vkey", "Vehicle key"),
                ImageFieldSpec("backview", "Vehicle back view"),
                ImageFieldSpec("frontview", "Vehicle front view"),
                ImageFieldSpec("chassis_capture", "Chassis capture"),
                ImageFieldSpec("extra_pic1", "Extra picture 1"),
                ImageFieldSpec("extra_pic2", "Extra picture 2"),
                ImageFieldSpec("extra_pic3", "Extra picture 3"),
                ImageFieldSpec("extra_pic4", "Extra picture 4")
            )
        ),
        CaptureKind.CONTAINER to CaptureSchema(
            label = "Container",
            endpoint = "/ped-save-container",
            titleKey = "container_number",
            description = "Container damage capture with one required image and optional follow-up evidence.",
            textFields = listOf(
                TextFieldSpec("container_number", "Container number", required = true, placeholder = "Enter container number"),
                TextFieldSpec("activity", "Activity", placeholder = "Unloading, loading, inspection..."),
                TextFieldSpec("container_movement", "Container movement", placeholder = "Inbound, outbound..."),
                TextFieldSpec("barging_activity", "Barging activity", placeholder = "Optional"),
                TextFieldSpec("remarks", "Remarks", multiline = true, placeholder = "Add field notes")
            ),
            imageFields = listOf(
                ImageFieldSpec("damage_pic1", "Damage image 1", required = true),
                ImageFieldSpec("damage_pic2", "Damage image 2"),
                ImageFieldSpec("damage_pic3", "Damage image 3"),
                ImageFieldSpec("damage_pic4", "Damage image 4")
            )
        ),
        CaptureKind.SCRAP to CaptureSchema(
            label = "Scrap",
            endpoint = "/ped-save-scrapovertime",
            titleKey = "chassis",
            description = "Record the vehicle status and location, with photos to support your notes.",
            textFields = listOf(
                TextFieldSpec("chassis", "Chassis number", required = true, placeholder = "Enter chassis number"),
                TextFieldSpec("status", "Status", placeholder = "Scrap, hold, overtime..."),
                TextFieldSpec("location", "Location", placeholder = "Yard or block location"),
                TextFieldSpec("section", "Section", placeholder = "Section or zone")
            ),
            imageFields = listOf(
                ImageFieldSpec("front_view", "Front view"),
                ImageFieldSpec("rear_view", "Rear view"),
                ImageFieldSpec("other_view", "Other view")
            )
        )
    )
}
