#ifndef X3TRAINER_BROADCASTER_H
#define X3TRAINER_BROADCASTER_H

#include <app.h>
#include <Elementary.h>
#include <Ecore.h>
#include <efl_extension.h>
#include <dlog.h>

#include <bluetooth.h>
#include <sensor.h>
#include <privacy_privilege_manager.h>
#include <system_info.h>
#include <device/power.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "X3TrainerDirect"

#if !defined(PACKAGE)
#define PACKAGE "org.example.x3trainerbroadcaster"
#endif

#define HEALTH_PRIVILEGE "http://tizen.org/privilege/healthinfo"

#define UUID_HEART_RATE_SERVICE "180D"
#define UUID_HEART_RATE_MEASUREMENT "2A37"
#define UUID_RUNNING_SPEED_CADENCE_SERVICE "1814"
#define UUID_RUNNING_SPEED_CADENCE_MEASUREMENT "2A53"
#define UUID_RUNNING_SPEED_CADENCE_FEATURE "2A54"
#define UUID_CLIENT_CHARACTERISTIC_CONFIGURATION "2902"

#endif
