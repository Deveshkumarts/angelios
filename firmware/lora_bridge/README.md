# iTANTRA LoRa bridge

Firmware that turns an ESP32 + SX1262 board into a USB radio modem for the iTANTRA app.

## Hardware

Default pins are for the **Heltec WiFi LoRa 32 V3**. Other boards (LilyGO T3S3, RP2040-LoRa) work after you change the `PIN_*` defines at the top of `lora_bridge.ino`.

## Flash

1. Install the Arduino IDE, the ESP32 board package (Espressif), and the **RadioLib** library (6.x).
2. Open `lora_bridge.ino`, select your board, and upload it.
3. Connect the board to the phone with a USB-OTG cable. Android shows a prompt to open iTANTRA; accept it to grant USB access.

The LoRa bearer appears as available once the bridge is attached. Every bridge in the team must use the same radio settings: 866 MHz, SF9, BW 125 kHz, CR 4/7, sync word 0x12.

## Limits

- One LoRa frame carries at most 255 bytes. The app only sends packets that fit, typically compact semantic packets, ACKs, and SOS beacons.
- SF9 airtime is about 0.2–0.6 s per frame, so discovery beacons go over LoRa only every 10 s.
- Check your local regulations. India's licence-free band is 865–867 MHz.
