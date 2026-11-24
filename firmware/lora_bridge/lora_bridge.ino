/*
 * iTANTRA LoRa bridge firmware  (ESP32 + Semtech SX1262)
 * -----------------------------------------------------------------------------
 * Turns an ESP32/SX1262 board into a transparent radio modem for the iTANTRA
 * Android app, connected over USB-OTG (see app LoraTransport.kt).
 *
 * Serial framing @115200 8N1:
 *   phone -> radio : A5 5A lenHi lenLo <packet bytes>                (len <= 255)
 *   radio -> phone : A5 5A lenHi lenLo rssi(int8) snr(int8) <bytes>  (len = 2 + n)
 *
 * Radio: 866.0 MHz (India 865-867 MHz licence-free band), BW 125 kHz, SF9,
 * CR 4/7, private sync word 0x12, 14 dBm. All iTANTRA bridges must match.
 *
 * Library: RadioLib (Arduino Library Manager, "RadioLib" by Jan Gromes, 6.x)
 * Default pins: Heltec WiFi LoRa 32 V3. Change the PIN_* defines for other boards.
 */
#include <RadioLib.h>

#define PIN_LORA_NSS   8
#define PIN_LORA_DIO1 14
#define PIN_LORA_RST  12
#define PIN_LORA_BUSY 13
#define PIN_LORA_SCK   9
#define PIN_LORA_MISO 11
#define PIN_LORA_MOSI 10

#define LORA_FREQ_MHZ   866.0
#define LORA_BW_KHZ     125.0
#define LORA_SF         9
#define LORA_CR         7
#define LORA_SYNC_WORD  0x12
#define LORA_POWER_DBM  14
#define LORA_PREAMBLE   8

#define MAX_FRAME 255
static const uint8_t SYNC0 = 0xA5, SYNC1 = 0x5A;

SPIClass loraSpi(FSPI);
SX1262 radio = new Module(PIN_LORA_NSS, PIN_LORA_DIO1, PIN_LORA_RST, PIN_LORA_BUSY, loraSpi);

volatile bool rxFlag = false;
#if defined(ESP8266) || defined(ESP32)
ICACHE_RAM_ATTR
#endif
void onDio1() { rxFlag = true; }

// --- serial frame parser state ---------------------------------------------
enum ParseState { WAIT_S0, WAIT_S1, LEN_HI, LEN_LO, BODY };
ParseState pState = WAIT_S0;
uint16_t pLen = 0, pPos = 0;
uint8_t txBuf[MAX_FRAME];

void startReceive() {
  int st = radio.startReceive();
  if (st != RADIOLIB_ERR_NONE) { Serial1.printf("startReceive err %d\n", st); }
}

void transmitFrame(const uint8_t *data, size_t len) {
  int st = radio.transmit(data, len);   // blocking; ~200-600 ms at SF9 for 40-255 B
  if (st != RADIOLIB_ERR_NONE) { Serial1.printf("tx err %d\n", st); }
  startReceive();
}

void pollSerial() {
  while (Serial.available()) {
    uint8_t b = Serial.read();
    switch (pState) {
      case WAIT_S0: if (b == SYNC0) pState = WAIT_S1; break;
      case WAIT_S1: pState = (b == SYNC1) ? LEN_HI : (b == SYNC0 ? WAIT_S1 : WAIT_S0); break;
      case LEN_HI:  pLen = (uint16_t)b << 8; pState = LEN_LO; break;
      case LEN_LO:
        pLen |= b; pPos = 0;
        pState = (pLen == 0 || pLen > MAX_FRAME) ? WAIT_S0 : BODY;
        break;
      case BODY:
        txBuf[pPos++] = b;
        if (pPos >= pLen) { transmitFrame(txBuf, pLen); pState = WAIT_S0; }
        break;
    }
  }
}

void forwardReceived() {
  rxFlag = false;
  size_t len = radio.getPacketLength();
  if (len == 0 || len > MAX_FRAME) { startReceive(); return; }
  uint8_t buf[MAX_FRAME];
  int st = radio.readData(buf, len);
  if (st == RADIOLIB_ERR_NONE) {
    int8_t rssi = (int8_t)constrain((int)radio.getRSSI(), -127, 0);
    int8_t snr  = (int8_t)constrain((int)radio.getSNR(), -128, 127);
    uint16_t flen = len + 2;
    uint8_t hdr[6] = { SYNC0, SYNC1, (uint8_t)(flen >> 8), (uint8_t)flen, (uint8_t)rssi, (uint8_t)snr };
    Serial.write(hdr, sizeof(hdr));
    Serial.write(buf, len);
  }
  startReceive();
}

void setup() {
  Serial.begin(115200);                 // USB link to the phone (data only)
  Serial1.begin(115200);                // optional debug UART
  loraSpi.begin(PIN_LORA_SCK, PIN_LORA_MISO, PIN_LORA_MOSI, PIN_LORA_NSS);

  int st = radio.begin(LORA_FREQ_MHZ, LORA_BW_KHZ, LORA_SF, LORA_CR, LORA_SYNC_WORD,
                       LORA_POWER_DBM, LORA_PREAMBLE);
  if (st != RADIOLIB_ERR_NONE) {
    Serial1.printf("SX1262 init failed: %d\n", st);
    while (true) delay(1000);
  }
  radio.setDio2AsRfSwitch(true);
  radio.setCRC(true);
  radio.setDio1Action(onDio1);
  startReceive();
}

void loop() {
  if (rxFlag) forwardReceived();
  pollSerial();
}
