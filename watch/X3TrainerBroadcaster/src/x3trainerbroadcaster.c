#include "x3trainerbroadcaster.h"

typedef struct appdata {
	Evas_Object *win;
	Evas_Object *conform;
	Evas_Object *table;
	Evas_Object *label;

	sensor_listener_h hr_listener;
	sensor_listener_h pedometer_listener;
	bool health_granted;
	bool sensors_started;
	bool hr_available;
	bool pedometer_available;
	bool cpu_locked;

	bool bt_initialized;
	bool gatt_initialized;
	bool server_started;
	bool advertising;
	bool gatt_connected;
	bool hr_subscribed;
	bool rsc_subscribed;
	bt_advertiser_h advertiser;
	Ecore_Timer *advertising_restart_timer;
	bt_gatt_server_h server;
	bt_gatt_h hr_service;
	bt_gatt_h hr_measurement;
	bt_gatt_h rsc_service;
	bt_gatt_h rsc_measurement;
	bt_gatt_h rsc_feature;

	int heart_rate;
	int cadence;
	int last_total_steps;
	double cadence_window_started_at;
	double last_step_at;
	char status[96];
} appdata_s;

static void render(appdata_s *ad);
static void begin_direct_broadcast(appdata_s *ad);
static void stop_bluetooth(appdata_s *ad);
static bool start_advertising(appdata_s *ad);
static void schedule_advertising_restart(appdata_s *ad);

static void set_status(appdata_s *ad, const char *status)
{
	if (!ad || !status)
		return;

	snprintf(ad->status, sizeof(ad->status), "%s", status);
	dlog_print(DLOG_INFO, LOG_TAG, "%s", status);
	render(ad);
}

static void render(appdata_s *ad)
{
	char markup[1400];
	char hr_text[16];
	char cadence_text[16];
	const char *link_color;

	if (!ad || !ad->label)
		return;

	if (ad->heart_rate > 0)
		snprintf(hr_text, sizeof(hr_text), "%d", ad->heart_rate);
	else
		snprintf(hr_text, sizeof(hr_text), "--");

	if (ad->pedometer_available)
		snprintf(cadence_text, sizeof(cadence_text), "%d", ad->cadence);
	else
		snprintf(cadence_text, sizeof(cadence_text), "--");

	link_color = (ad->gatt_connected && ad->hr_subscribed) ? "#50F29B" : "#5EDBFF";

	snprintf(markup, sizeof(markup),
		"<align=center>"
		"<font_size=24><color=#5EDBFF><b>X3TRAINER LINK</b></color></font_size><br/>"
		"<font_size=15><color=#8192A8>ACTIVE2  &gt;  PHONE  &gt;  X3</color></font_size><br/><br/>"
		"<font_size=48><color=#FF5D7D><b>%s</b></color></font_size>"
		"<font_size=18><color=#B8C6D9> bpm</color></font_size><br/>"
		"<font_size=13><color=#8192A8>HEART RATE</color></font_size><br/><br/>"
		"<font_size=31><color=#F5F8FC><b>%s</b></color></font_size>"
		"<font_size=16><color=#B8C6D9> spm</color></font_size><br/>"
		"<font_size=13><color=#8192A8>CADENCE</color></font_size><br/><br/>"
		"<font_size=16><color=%s><b>%s</b></color></font_size><br/>"
		"<font_size=11><color=#64758A>LIVE ONLY  |  NOT STORED</color></font_size>"
		"</align>",
		hr_text, cadence_text, link_color, ad->status[0] ? ad->status : "STARTING");

	elm_object_text_set(ad->label, markup);
}

static void notification_sent_cb(int result, const char *remote_address,
		bt_gatt_server_h server, bt_gatt_h characteristic, bool completed,
		void *user_data)
{
	(void)remote_address;
	(void)server;
	(void)characteristic;
	(void)completed;
	(void)user_data;

	if (result != BT_ERROR_NONE)
		dlog_print(DLOG_WARN, LOG_TAG, "GATT notification failed: %d", result);
}

static void publish_heart_rate(appdata_s *ad)
{
	char value[3];
	int length;
	int ret;

	if (!ad || !ad->hr_measurement || ad->heart_rate <= 0)
		return;

	if (ad->heart_rate <= 255) {
		value[0] = 0x00;
		value[1] = (char)(ad->heart_rate & 0xff);
		length = 2;
	} else {
		value[0] = 0x01;
		value[1] = (char)(ad->heart_rate & 0xff);
		value[2] = (char)((ad->heart_rate >> 8) & 0xff);
		length = 3;
	}

	ret = bt_gatt_set_value(ad->hr_measurement, value, length);
	if (ret == BT_ERROR_NONE && ad->hr_subscribed)
		bt_gatt_server_notify_characteristic_changed_value(ad->hr_measurement,
			notification_sent_cb, NULL, ad);
}

static void publish_cadence(appdata_s *ad)
{
	char value[4];
	unsigned int speed_raw;
	int cadence;
	int ret;

	if (!ad || !ad->rsc_measurement)
		return;

	cadence = ad->cadence;
	if (cadence < 0)
		cadence = 0;
	if (cadence > 255)
		cadence = 255;

	/* Estimated speed uses a conservative 0.78 m step length. RSC speed is 1/256 m/s. */
	speed_raw = (unsigned int)((cadence * 333 + 50) / 100);
	if (speed_raw > 65535)
		speed_raw = 65535;

	value[0] = 0x00;
	value[1] = (char)(speed_raw & 0xff);
	value[2] = (char)((speed_raw >> 8) & 0xff);
	value[3] = (char)(cadence & 0xff);

	ret = bt_gatt_set_value(ad->rsc_measurement, value, sizeof(value));
	if (ret == BT_ERROR_NONE && ad->rsc_subscribed)
		bt_gatt_server_notify_characteristic_changed_value(ad->rsc_measurement,
			notification_sent_cb, NULL, ad);
}

static void hr_events_cb(sensor_h sensor, sensor_event_s events[], int events_count,
		void *user_data)
{
	appdata_s *ad = user_data;
	sensor_event_s *event;
	int bpm;

	(void)sensor;
	if (!ad || !events || events_count <= 0)
		return;

	event = &events[events_count - 1];
	if (event->value_count < 1)
		return;

	bpm = (int)(event->values[0] + 0.5f);
	if (bpm <= 0 || bpm > 300)
		return;

	ad->heart_rate = bpm;
	publish_heart_rate(ad);
	render(ad);
}

static void pedometer_events_cb(sensor_h sensor, sensor_event_s events[], int events_count,
		void *user_data)
{
	appdata_s *ad = user_data;
	sensor_event_s *event;
	double now;
	double elapsed;
	int total_steps;
	int delta;
	int measured_cadence;

	(void)sensor;
	if (!ad || !events || events_count <= 0)
		return;

	event = &events[events_count - 1];
	if (event->value_count < 1)
		return;

	total_steps = (int)(event->values[0] + 0.5f);
	now = ecore_time_get();

	if (ad->cadence_window_started_at <= 0.0 || total_steps < ad->last_total_steps) {
		ad->last_total_steps = total_steps;
		ad->cadence_window_started_at = now;
		ad->last_step_at = now;
		return;
	}

	if (total_steps > ad->last_total_steps)
		ad->last_step_at = now;

	elapsed = now - ad->cadence_window_started_at;
	if (elapsed >= 4.0) {
		delta = total_steps - ad->last_total_steps;
		measured_cadence = (int)((delta * 60.0 / elapsed) + 0.5);
		if (measured_cadence < 0)
			measured_cadence = 0;
		if (measured_cadence > 255)
			measured_cadence = 255;

		ad->cadence = measured_cadence;
		ad->last_total_steps = total_steps;
		ad->cadence_window_started_at = now;
	}

	if (now - ad->last_step_at > 4.5)
		ad->cadence = 0;

	publish_cadence(ad);
	render(ad);
}

static int configure_listener(sensor_type_e type, sensor_events_cb callback,
		sensor_listener_h *listener, void *user_data)
{
	sensor_h sensor = NULL;
	int ret;

	ret = sensor_get_default_sensor(type, &sensor);
	if (ret != SENSOR_ERROR_NONE)
		return ret;

	ret = sensor_create_listener(sensor, listener);
	if (ret != SENSOR_ERROR_NONE)
		return ret;

	sensor_listener_set_option(*listener, SENSOR_OPTION_ALWAYS_ON);
	sensor_listener_set_attribute_int(*listener, SENSOR_ATTRIBUTE_PAUSE_POLICY,
		SENSOR_PAUSE_NONE);
	sensor_listener_set_interval(*listener, 1000);

	ret = sensor_listener_set_events_cb(*listener, callback, user_data);
	if (ret != SENSOR_ERROR_NONE)
		return ret;

	return sensor_listener_start(*listener);
}

static void start_sensors(appdata_s *ad)
{
	int hr_ret;
	int pedometer_ret;

	if (!ad || ad->sensors_started)
		return;

	hr_ret = configure_listener(SENSOR_HRM, hr_events_cb, &ad->hr_listener, ad);
	ad->hr_available = (hr_ret == SENSOR_ERROR_NONE);
	if (!ad->hr_available)
		dlog_print(DLOG_ERROR, LOG_TAG, "HR sensor start failed: %d", hr_ret);

	pedometer_ret = configure_listener(SENSOR_HUMAN_PEDOMETER, pedometer_events_cb,
		&ad->pedometer_listener, ad);
	ad->pedometer_available = (pedometer_ret == SENSOR_ERROR_NONE);
	if (!ad->pedometer_available)
		dlog_print(DLOG_WARN, LOG_TAG, "Pedometer start failed: %d", pedometer_ret);

	ad->sensors_started = ad->hr_available || ad->pedometer_available;
	if (!ad->hr_available)
		set_status(ad, "HR SENSOR UNAVAILABLE");
}

static void stop_sensors(appdata_s *ad)
{
	if (!ad)
		return;

	if (ad->hr_listener) {
		sensor_listener_stop(ad->hr_listener);
		sensor_destroy_listener(ad->hr_listener);
		ad->hr_listener = NULL;
	}
	if (ad->pedometer_listener) {
		sensor_listener_stop(ad->pedometer_listener);
		sensor_destroy_listener(ad->pedometer_listener);
		ad->pedometer_listener = NULL;
	}
	ad->sensors_started = false;
}

static void read_requested_cb(const char *remote_address, int request_id,
		bt_gatt_server_h server, bt_gatt_h gatt_handle, int offset, void *user_data)
{
	(void)remote_address;
	(void)server;
	(void)gatt_handle;
	(void)user_data;
	bt_gatt_server_send_response(request_id, BT_GATT_REQUEST_TYPE_READ, offset,
		BT_ERROR_NONE, NULL, 0);
}

static void subscription_changed_cb(bool notify, bt_gatt_server_h server,
		bt_gatt_h gatt_handle, void *user_data)
{
	appdata_s *ad = user_data;
	(void)server;

	if (!ad)
		return;

	if (gatt_handle == ad->hr_measurement)
		ad->hr_subscribed = notify;
	else if (gatt_handle == ad->rsc_measurement)
		ad->rsc_subscribed = notify;

	if (ad->gatt_connected && ad->hr_subscribed)
		set_status(ad, "LIVE TO PHONE");
	else if (ad->gatt_connected)
		set_status(ad, "PHONE CONNECTED - SUBSCRIBING");
	else
		set_status(ad, "WAITING FOR PHONE BRIDGE");

	if (notify) {
		publish_heart_rate(ad);
		publish_cadence(ad);
	}
}

static void connection_changed_cb(int result, bool connected,
		const char *remote_address, void *user_data)
{
	appdata_s *ad = user_data;

	if (!ad)
		return;

	dlog_print(DLOG_INFO, LOG_TAG, "GATT connection result=%d connected=%d remote=%s",
		result, connected, remote_address ? remote_address : "unknown");

	ad->gatt_connected = connected && result == BT_ERROR_NONE;
	if (ad->gatt_connected)
		set_status(ad, "PHONE CONNECTED - SUBSCRIBING");
	else {
		ad->hr_subscribed = false;
		ad->rsc_subscribed = false;
		set_status(ad, "WAITING FOR PHONE BRIDGE");
		schedule_advertising_restart(ad);
	}
}

static void advertising_state_cb(int result, bt_advertiser_h advertiser,
		bt_adapter_le_advertising_state_e state, void *user_data)
{
	appdata_s *ad = user_data;
	(void)advertiser;

	if (!ad)
		return;

	dlog_print(DLOG_INFO, LOG_TAG, "Advertising state result=%d state=%d",
		result, state);

	ad->advertising = (result == BT_ERROR_NONE &&
		state == BT_ADAPTER_LE_ADVERTISING_STARTED);
	if (result != BT_ERROR_NONE)
		set_status(ad, "BLE ADVERTISING FAILED");
	else if (ad->gatt_connected)
		set_status(ad, ad->hr_subscribed ? "LIVE TO PHONE" : "PHONE CONNECTED - SUBSCRIBING");
	else if (ad->advertising)
		set_status(ad, "WAITING FOR PHONE BRIDGE");
	else if (ad->server_started && !ad->gatt_connected)
		schedule_advertising_restart(ad);
}

static Eina_Bool advertising_restart_timer_cb(void *data)
{
	appdata_s *ad = data;

	if (!ad)
		return ECORE_CALLBACK_CANCEL;

	ad->advertising_restart_timer = NULL;
	if (ad->server_started && ad->advertiser && !ad->advertising &&
			!ad->gatt_connected)
		start_advertising(ad);

	return ECORE_CALLBACK_CANCEL;
}

static void schedule_advertising_restart(appdata_s *ad)
{
	if (!ad || !ad->server_started || !ad->advertiser || ad->advertising ||
			ad->gatt_connected || ad->advertising_restart_timer)
		return;

	ad->advertising_restart_timer = ecore_timer_add(0.75,
		advertising_restart_timer_cb, ad);
}

static bool start_advertising(appdata_s *ad)
{
	int ret;

	if (!ad || !ad->server_started || !ad->advertiser)
		return false;
	if (ad->advertising || ad->gatt_connected)
		return true;

	ret = bt_adapter_le_start_advertising_new(ad->advertiser,
		advertising_state_cb, ad);
	if (ret != BT_ERROR_NONE && ret != BT_ERROR_ALREADY_DONE) {
		dlog_print(DLOG_ERROR, LOG_TAG, "Advertising start failed: %d", ret);
		set_status(ad, "BLE ADVERTISING FAILED");
		return false;
	}

	set_status(ad, "STARTING BLE TO PHONE");
	return true;
}

static int add_measurement_service(appdata_s *ad, const char *service_uuid,
		const char *measurement_uuid, bt_gatt_h *service, bt_gatt_h *measurement)
{
	char initial_value[4] = {0, 0, 0, 0};
	char cccd_value[2] = {0, 0};
	bt_gatt_h cccd = NULL;
	int ret;

	ret = bt_gatt_service_create(service_uuid, BT_GATT_SERVICE_TYPE_PRIMARY, service);
	if (ret != BT_ERROR_NONE)
		return ret;

	ret = bt_gatt_characteristic_create(measurement_uuid, BT_GATT_PERMISSION_READ,
		BT_GATT_PROPERTY_READ | BT_GATT_PROPERTY_NOTIFY, initial_value,
		strcmp(measurement_uuid, UUID_HEART_RATE_MEASUREMENT) == 0 ? 2 : 4,
		measurement);
	if (ret != BT_ERROR_NONE)
		return ret;

	ret = bt_gatt_descriptor_create(UUID_CLIENT_CHARACTERISTIC_CONFIGURATION,
		BT_GATT_PERMISSION_READ | BT_GATT_PERMISSION_WRITE,
		cccd_value, sizeof(cccd_value), &cccd);
	if (ret != BT_ERROR_NONE)
		return ret;

	ret = bt_gatt_characteristic_add_descriptor(*measurement, cccd);
	if (ret != BT_ERROR_NONE)
		return ret;

	ret = bt_gatt_service_add_characteristic(*service, *measurement);
	if (ret != BT_ERROR_NONE)
		return ret;

	bt_gatt_server_set_read_value_requested_cb(*measurement, read_requested_cb, ad);
	return bt_gatt_server_set_characteristic_notification_state_change_cb(*measurement,
		subscription_changed_cb, ad);
}

static bool start_bluetooth(appdata_s *ad)
{
	bt_adapter_state_e adapter_state;
	char rsc_feature_value[2] = {0, 0};
	int ret;

	if (!ad)
		return false;
	if (ad->server_started)
		return true;

	ret = bt_initialize();
	if (ret != BT_ERROR_NONE) {
		set_status(ad, "BLUETOOTH INIT FAILED");
		return false;
	}
	ad->bt_initialized = true;

	ret = bt_adapter_get_state(&adapter_state);
	if (ret != BT_ERROR_NONE || adapter_state != BT_ADAPTER_ENABLED) {
		set_status(ad, "TURN BLUETOOTH ON");
		return false;
	}

	ret = bt_gatt_server_initialize();
	if (ret != BT_ERROR_NONE) {
		dlog_print(DLOG_ERROR, LOG_TAG, "GATT server unsupported/init failed: %d", ret);
		set_status(ad, "GATT SERVER NOT SUPPORTED");
		return false;
	}
	ad->gatt_initialized = true;

	ret = bt_gatt_server_create(&ad->server);
	if (ret != BT_ERROR_NONE)
		goto fail;

	ret = add_measurement_service(ad, UUID_HEART_RATE_SERVICE,
		UUID_HEART_RATE_MEASUREMENT, &ad->hr_service, &ad->hr_measurement);
	if (ret != BT_ERROR_NONE)
		goto fail;

	ret = add_measurement_service(ad, UUID_RUNNING_SPEED_CADENCE_SERVICE,
		UUID_RUNNING_SPEED_CADENCE_MEASUREMENT, &ad->rsc_service,
		&ad->rsc_measurement);
	if (ret != BT_ERROR_NONE)
		goto fail;

	ret = bt_gatt_characteristic_create(UUID_RUNNING_SPEED_CADENCE_FEATURE,
		BT_GATT_PERMISSION_READ, BT_GATT_PROPERTY_READ, rsc_feature_value,
		sizeof(rsc_feature_value), &ad->rsc_feature);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_gatt_service_add_characteristic(ad->rsc_service, ad->rsc_feature);
	if (ret != BT_ERROR_NONE)
		goto fail;
	bt_gatt_server_set_read_value_requested_cb(ad->rsc_feature, read_requested_cb, ad);

	ret = bt_gatt_server_register_service(ad->server, ad->hr_service);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_gatt_server_register_service(ad->server, ad->rsc_service);
	if (ret != BT_ERROR_NONE)
		goto fail;

	ret = bt_gatt_set_connection_state_changed_cb(connection_changed_cb, ad);
	if (ret != BT_ERROR_NONE)
		goto fail;

	ret = bt_gatt_server_start();
	if (ret != BT_ERROR_NONE && ret != BT_ERROR_ALREADY_DONE)
		goto fail;
	ad->server_started = true;

	ret = bt_adapter_le_create_advertiser(&ad->advertiser);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_adapter_le_set_advertising_mode(ad->advertiser,
		BT_ADAPTER_LE_ADVERTISING_MODE_LOW_LATENCY);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_adapter_le_set_advertising_connectable(ad->advertiser, true);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_adapter_le_add_advertising_service_uuid(ad->advertiser,
		BT_ADAPTER_LE_PACKET_ADVERTISING, UUID_HEART_RATE_SERVICE);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_adapter_le_add_advertising_service_uuid(ad->advertiser,
		BT_ADAPTER_LE_PACKET_ADVERTISING, UUID_RUNNING_SPEED_CADENCE_SERVICE);
	if (ret != BT_ERROR_NONE)
		goto fail;
	ret = bt_adapter_le_set_advertising_appearance(ad->advertiser,
		BT_ADAPTER_LE_PACKET_ADVERTISING, 0x0340);
	if (ret != BT_ERROR_NONE)
		dlog_print(DLOG_WARN, LOG_TAG, "Advertising appearance unavailable: %d", ret);
	ret = bt_adapter_le_set_advertising_device_name(ad->advertiser,
		BT_ADAPTER_LE_PACKET_SCAN_RESPONSE, true);
	if (ret != BT_ERROR_NONE)
		dlog_print(DLOG_WARN, LOG_TAG, "Advertising device name unavailable: %d", ret);

	if (!start_advertising(ad)) {
		ret = BT_ERROR_OPERATION_FAILED;
		goto fail;
	}
	return true;

fail:
	dlog_print(DLOG_ERROR, LOG_TAG, "BLE GATT setup failed: %d", ret);
	set_status(ad, "BLE SETUP FAILED");
	stop_bluetooth(ad);
	return false;
}

static void stop_bluetooth(appdata_s *ad)
{
	if (!ad)
		return;

	/* Prevent asynchronous STOPPED callbacks from restarting a torn-down advertiser. */
	ad->server_started = false;
	if (ad->advertising_restart_timer) {
		ecore_timer_del(ad->advertising_restart_timer);
		ad->advertising_restart_timer = NULL;
	}

	if (ad->advertiser) {
		bt_adapter_le_stop_advertising(ad->advertiser);
		bt_adapter_le_destroy_advertiser(ad->advertiser);
		ad->advertiser = NULL;
	}
	ad->advertising = false;
	ad->gatt_connected = false;
	ad->hr_subscribed = false;
	ad->rsc_subscribed = false;

	if (ad->bt_initialized)
		bt_gatt_unset_connection_state_changed_cb();
	if (ad->server)
		bt_gatt_server_unregister_all_services(ad->server);
	if (ad->hr_service)
		bt_gatt_service_destroy(ad->hr_service);
	if (ad->rsc_service)
		bt_gatt_service_destroy(ad->rsc_service);
	ad->hr_service = NULL;
	ad->hr_measurement = NULL;
	ad->rsc_service = NULL;
	ad->rsc_measurement = NULL;
	ad->rsc_feature = NULL;

	if (ad->server) {
		bt_gatt_server_destroy(ad->server);
		ad->server = NULL;
	}
	if (ad->gatt_initialized) {
		bt_gatt_server_deinitialize();
		ad->gatt_initialized = false;
	}
	if (ad->bt_initialized) {
		bt_deinitialize();
		ad->bt_initialized = false;
	}
}

static void begin_direct_broadcast(appdata_s *ad)
{
	if (!ad || !ad->health_granted)
		return;

	if (!ad->cpu_locked) {
		ad->cpu_locked = (device_power_request_lock(POWER_LOCK_CPU, 0) == 0);
		if (!ad->cpu_locked)
			dlog_print(DLOG_WARN, LOG_TAG, "CPU lock unavailable; screen-off updates may pause");
	}

	start_sensors(ad);
	start_bluetooth(ad);
}

static void health_permission_response_cb(ppm_call_cause_e cause,
		ppm_request_result_e result, const char *privilege, void *user_data)
{
	appdata_s *ad = user_data;
	(void)privilege;

	if (!ad)
		return;

	ad->health_granted = (cause == PRIVACY_PRIVILEGE_MANAGER_CALL_CAUSE_ANSWER &&
		result == PRIVACY_PRIVILEGE_MANAGER_REQUEST_RESULT_ALLOW_FOREVER);
	if (ad->health_granted) {
		set_status(ad, "STARTING SENSORS");
		begin_direct_broadcast(ad);
	} else {
		set_status(ad, "HEALTH ACCESS REQUIRED");
	}
}

static void request_health_permission(appdata_s *ad)
{
	ppm_check_result_e result;
	int ret;

	if (!ad)
		return;

	ret = ppm_check_permission(HEALTH_PRIVILEGE, &result);
	if (ret != PRIVACY_PRIVILEGE_MANAGER_ERROR_NONE) {
		set_status(ad, "PERMISSION CHECK FAILED");
		return;
	}

	if (result == PRIVACY_PRIVILEGE_MANAGER_CHECK_RESULT_ALLOW) {
		ad->health_granted = true;
		begin_direct_broadcast(ad);
	} else if (result == PRIVACY_PRIVILEGE_MANAGER_CHECK_RESULT_ASK) {
		set_status(ad, "ALLOW HEALTH ACCESS");
		ret = ppm_request_permission(HEALTH_PRIVILEGE,
			health_permission_response_cb, ad);
		if (ret != PRIVACY_PRIVILEGE_MANAGER_ERROR_NONE)
			set_status(ad, "PERMISSION REQUEST FAILED");
	} else {
		set_status(ad, "HEALTH ACCESS DENIED");
	}
}

static void retry_tapped_cb(void *data, Evas *evas, Evas_Object *obj,
		void *event_info)
{
	appdata_s *ad = data;
	(void)evas;
	(void)obj;
	(void)event_info;

	if (!ad)
		return;

	if (!ad->health_granted)
		request_health_permission(ad);
	else if (!ad->server_started) {
		stop_bluetooth(ad);
		start_bluetooth(ad);
	}
}

static void win_delete_request_cb(void *data, Evas_Object *obj, void *event_info)
{
	(void)data;
	(void)obj;
	(void)event_info;
	ui_app_exit();
}

static void win_back_cb(void *data, Evas_Object *obj, void *event_info)
{
	appdata_s *ad = data;
	(void)obj;
	(void)event_info;

	/* Keep the native broadcaster alive when the watch UI is lowered. */
	elm_win_lower(ad->win);
}

static void create_base_gui(appdata_s *ad)
{
	Evas_Object *background;

	ad->win = elm_win_util_standard_add(PACKAGE, "X3Trainer Link");
	elm_win_autodel_set(ad->win, EINA_TRUE);
	evas_object_smart_callback_add(ad->win, "delete,request", win_delete_request_cb, ad);
	eext_object_event_callback_add(ad->win, EEXT_CALLBACK_BACK, win_back_cb, ad);

	ad->conform = elm_conformant_add(ad->win);
	elm_win_indicator_mode_set(ad->win, ELM_WIN_INDICATOR_HIDE);
	evas_object_size_hint_weight_set(ad->conform, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
	elm_win_resize_object_add(ad->win, ad->conform);
	evas_object_show(ad->conform);

	ad->table = elm_table_add(ad->conform);
	evas_object_size_hint_weight_set(ad->table, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
	elm_object_content_set(ad->conform, ad->table);
	evas_object_show(ad->table);

	background = evas_object_rectangle_add(evas_object_evas_get(ad->table));
	evas_object_color_set(background, 5, 10, 18, 255);
	evas_object_size_hint_weight_set(background, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
	evas_object_size_hint_align_set(background, EVAS_HINT_FILL, EVAS_HINT_FILL);
	elm_table_pack(ad->table, background, 0, 0, 1, 1);
	evas_object_show(background);

	ad->label = elm_label_add(ad->table);
	elm_label_line_wrap_set(ad->label, ELM_WRAP_WORD);
	evas_object_size_hint_weight_set(ad->label, EVAS_HINT_EXPAND, EVAS_HINT_EXPAND);
	evas_object_size_hint_align_set(ad->label, EVAS_HINT_FILL, 0.5);
	elm_table_pack(ad->table, ad->label, 0, 0, 1, 1);
	evas_object_event_callback_add(ad->label, EVAS_CALLBACK_MOUSE_UP,
		retry_tapped_cb, ad);
	evas_object_show(ad->label);

	snprintf(ad->status, sizeof(ad->status), "STARTING");
	render(ad);
	evas_object_show(ad->win);
}

static bool app_create(void *data)
{
	appdata_s *ad = data;
	create_base_gui(ad);
	request_health_permission(ad);
	return true;
}

static void app_control(app_control_h app_control, void *data)
{
	(void)app_control;
	(void)data;
}

static void app_pause(void *data)
{
	/* Sensors and BLE intentionally continue for screen-off workouts. */
	(void)data;
}

static void app_resume(void *data)
{
	appdata_s *ad = data;
	render(ad);
}

static void app_terminate(void *data)
{
	appdata_s *ad = data;
	stop_sensors(ad);
	stop_bluetooth(ad);
	if (ad->cpu_locked) {
		device_power_release_lock(POWER_LOCK_CPU);
		ad->cpu_locked = false;
	}
}

static void ui_app_low_battery(app_event_info_h event_info, void *user_data)
{
	appdata_s *ad = user_data;
	(void)event_info;
	set_status(ad, "LOW BATTERY - BROADCASTING");
}

static void ui_app_low_memory(app_event_info_h event_info, void *user_data)
{
	(void)event_info;
	(void)user_data;
}

int main(int argc, char *argv[])
{
	appdata_s ad = {0, };
	ui_app_lifecycle_callback_s event_callback = {0, };
	app_event_handler_h handlers[2] = {NULL, };
	int ret;

	event_callback.create = app_create;
	event_callback.terminate = app_terminate;
	event_callback.pause = app_pause;
	event_callback.resume = app_resume;
	event_callback.app_control = app_control;

	ui_app_add_event_handler(&handlers[0], APP_EVENT_LOW_BATTERY,
		ui_app_low_battery, &ad);
	ui_app_add_event_handler(&handlers[1], APP_EVENT_LOW_MEMORY,
		ui_app_low_memory, &ad);

	ret = ui_app_main(argc, argv, &event_callback, &ad);
	if (ret != APP_ERROR_NONE)
		dlog_print(DLOG_ERROR, LOG_TAG, "ui_app_main failed: %d", ret);

	return ret;
}
