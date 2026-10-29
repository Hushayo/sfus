package mcbot.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Packet ids for protocol 777 (Minecraft 26.3), generated from the server's own
 * report rather than guessed.
 *
 * <p>Every id here comes from {@code generated/reports/packets.json}, which the
 * vanilla data generator emits:
 *
 * <pre>
 *   java -cp "server-26.3.jar;libraries/*" net.minecraft.data.Main --reports
 *   # -> generated/reports/packets.json
 * </pre>
 *
 * <p>That file is the server's registry of packet types, so it is authoritative
 * for this exact build. The previous table in this project was assembled from
 * packet sizes in a capture, which is how six ids ended up wrong at once:
 *
 * <pre>
 *   open_screen        0x33 -> 0x3C   (0x33 is low_disk_space_warning)
 *   container_close    0x13 -> 0x11   (0x13 is container_set_data)
 *   set_held_item      0x4A -> 0x6B
 *   entity_status      0x1D -> 0x22   (0x1D is debug/event)
 *   use_item_on        0x3A -> 0x42   (0x3A is set_game_rule)
 *   container_click    0x0F -> 0x12   (0x0F is command_suggestion)
 * </pre>
 *
 * <p>Each is still overridable at runtime for servers that differ:
 * {@code -Dmcbot.packet.open_screen=0x3C}. {@code -1} means "do not use this
 * packet"; modules check for that instead of sending something unverified.
 */
public final class PacketIds {
    private PacketIds() {}

    private static final Map<String, Integer> KNOWN = new LinkedHashMap<>();
    private static final Map<Integer, String> CLIENTBOUND_NAMES = new LinkedHashMap<>();
    private static final Map<Integer, String> SERVERBOUND_NAMES = new LinkedHashMap<>();

    private static int id(String name, int verified) {
        Integer v = Integer.getInteger("mcbot.packet." + name);
        int resolved = (v == null) ? verified : v;
        if (v != null) {
            System.out.println("[ids] override mcbot.packet." + name
                    + " = 0x" + Integer.toHexString(v) + " (verified 0x"
                    + Integer.toHexString(verified) + ")");
        }
        KNOWN.put(name, resolved);
        return resolved;
    }

    // ---------------------------------------------------------------- login --

    public static final int LOGIN_DISCONNECT = 0x00;
    public static final int LOGIN_ENCRYPTION_REQUEST = 0x01;
    public static final int LOGIN_SUCCESS = 0x02;
    public static final int LOGIN_COMPRESSION = 0x03;
    public static final int LOGIN_COOKIE_REQUEST = 0x05;

    public static final int LOGIN_HELLO = 0x00;
    public static final int LOGIN_KEY = 0x01;
    public static final int LOGIN_ACKNOWLEDGED = 0x03;
    public static final int LOGIN_COOKIE_RESPONSE = 0x04;

    // --------------------------------------------------------- configuration --

    public static final int CFG_COOKIE_REQUEST = 0x00;
    public static final int CFG_CUSTOM_PAYLOAD = 0x01;
    public static final int CFG_DISCONNECT = 0x02;
    public static final int CFG_FINISH_CONFIGURATION = 0x03;
    public static final int CFG_KEEP_ALIVE = 0x04;
    public static final int CFG_PING = 0x05;
    public static final int CFG_RESET_CHAT = 0x06;
    public static final int CFG_REGISTRY_DATA = 0x07;
    public static final int CFG_RESOURCE_PACK_POP = 0x08;
    public static final int CFG_RESOURCE_PACK_PUSH = 0x09;
    public static final int CFG_STORE_COOKIE = 0x0B;
    public static final int CFG_TRANSFER = 0x0C;
    public static final int CFG_UPDATE_ENABLED_FEATURES = 0x0D;
    public static final int CFG_UPDATE_TAGS = 0x0E;
    public static final int CFG_SELECT_KNOWN_PACKS = 0x0F;
    public static final int CFG_SERVER_LINKS = 0x11;

    public static final int CFG_SB_CLIENT_INFORMATION = 0x00;
    public static final int CFG_SB_COOKIE_RESPONSE = 0x01;
    public static final int CFG_SB_CUSTOM_PAYLOAD = 0x02;
    public static final int CFG_SB_FINISH_CONFIGURATION = 0x03;
    public static final int CFG_SB_KEEP_ALIVE = 0x04;
    public static final int CFG_SB_PONG = 0x05;
    public static final int CFG_SB_RESOURCE_PACK = 0x06;
    public static final int CFG_SB_SELECT_KNOWN_PACKS = 0x07;

    // ------------------------------------------------------ play clientbound --

    public static final int ADD_ENTITY = id("add_entity", 0x01);
    public static final int ANIMATE = id("animate", 0x02);
    public static final int BLOCK_CHANGED_ACK = id("block_changed_ack", 0x04);
    /** VarInt breaker entity id, BlockPos, byte destroy stage (0..9). */
    public static final int BLOCK_DESTRUCTION = id("block_destruction", 0x05);
    public static final int BLOCK_ENTITY_DATA = id("block_entity_data", 0x06);
    public static final int BLOCK_EVENT = id("block_event", 0x07);
    public static final int BLOCK_UPDATE = id("block_update", 0x08);
    public static final int BOSS_EVENT = id("boss_event", 0x09);
    public static final int CHUNK_BATCH_FINISHED = id("chunk_batch_finished", 0x0B);
    public static final int CHUNK_BATCH_START = id("chunk_batch_start", 0x0C);
    public static final int CONTAINER_CLOSE = id("container_close", 0x11);
    public static final int CONTAINER_SET_CONTENT = id("container_set_content", 0x12);
    public static final int CONTAINER_SET_DATA = id("container_set_data", 0x13);
    public static final int CONTAINER_SET_SLOT = id("container_set_slot", 0x14);
    public static final int COOLDOWN = id("cooldown", 0x16);
    public static final int CUSTOM_PAYLOAD = id("custom_payload", 0x18);
    public static final int DAMAGE_EVENT = id("damage_event", 0x19);
    public static final int DISCONNECT = id("disconnect", 0x20);
    public static final int DISGUISED_CHAT = id("disguised_chat", 0x21);
    /** entity_event / entity_status: VarInt entity id, byte status (2 = hurt). */
    public static final int ENTITY_STATUS = id("entity_status", 0x22);
    public static final int ENTITY_POSITION_SYNC = id("entity_position_sync", 0x23);
    public static final int EXPLODE = id("explode", 0x24);
    public static final int FORGET_LEVEL_CHUNK = id("forget_level_chunk", 0x26);
    public static final int GAME_EVENT = id("game_event", 0x27);
    public static final int GAME_RULE_VALUES = id("game_rule_values", 0x28);
    /** VarInt entity id, byte yaw: the "something hit me" animation. */
    public static final int HURT_ANIMATION = id("hurt_animation", 0x2B);
    public static final int INITIALIZE_BORDER = id("initialize_border", 0x2C);
    public static final int KEEP_ALIVE = id("keep_alive", 0x2D);
    public static final int LEVEL_CHUNK_WITH_LIGHT = id("level_chunk_with_light", 0x2E);
    public static final int LEVEL_EVENT = id("level_event", 0x2F);
    public static final int LIGHT_UPDATE = id("light_update", 0x31);
    public static final int LOGIN = id("login", 0x32);
    public static final int MERCHANT_OFFERS = id("merchant_offers", 0x35);
    public static final int MOVE_ENTITY_POS = id("move_entity_pos", 0x36);
    public static final int MOVE_ENTITY_POS_ROT = id("move_entity_pos_rot", 0x37);
    public static final int MOVE_ENTITY_ROT = id("move_entity_rot", 0x39);
    public static final int MOUNT_SCREEN_OPEN = id("mount_screen_open", 0x2A);
    public static final int OPEN_SCREEN = id("open_screen", 0x3C);
    public static final int OPEN_SIGN_EDITOR = id("open_sign_editor", 0x3D);
    public static final int PING = id("ping", 0x3E);
    public static final int PLACE_GHOST_RECIPE = id("place_ghost_recipe", 0x40);
    public static final int PLAYER_ABILITIES = id("player_abilities", 0x41);
    public static final int PLAYER_CHAT = id("player_chat", 0x42);
    public static final int PLAYER_COMBAT_END = id("player_combat_end", 0x43);
    public static final int PLAYER_COMBAT_ENTER = id("player_combat_enter", 0x44);
    public static final int PLAYER_COMBAT_KILL = id("player_combat_kill", 0x45);
    public static final int PLAYER_INFO_REMOVE = id("player_info_remove", 0x46);
    public static final int PLAYER_INFO_UPDATE = id("player_info_update", 0x47);
    public static final int PLAYER_LOOK_AT = id("player_look_at", 0x48);
    public static final int PLAYER_POSITION = id("player_position", 0x49);
    public static final int PLAYER_ROTATION = id("player_rotation", 0x4A);
    public static final int RECIPE_BOOK_ADD = id("recipe_book_add", 0x4B);
    public static final int RECIPE_BOOK_REMOVE = id("recipe_book_remove", 0x4C);
    public static final int RECIPE_BOOK_SETTINGS = id("recipe_book_settings", 0x4D);
    public static final int REMOVE_ENTITIES = id("remove_entities", 0x4E);
    public static final int REMOVE_MOB_EFFECT = id("remove_mob_effect", 0x4F);
    public static final int RESOURCE_PACK_POP = id("resource_pack_pop", 0x51);
    public static final int RESOURCE_PACK_PUSH = id("resource_pack_push", 0x52);
    public static final int RESPAWN = id("respawn", 0x54);
    public static final int ROTATE_HEAD = id("rotate_head", 0x55);
    public static final int SECTION_BLOCKS_UPDATE = id("section_blocks_update", 0x56);
    public static final int SERVER_DATA = id("server_data", 0x58);
    public static final int SET_ACTION_BAR_TEXT = id("set_action_bar_text", 0x59);
    public static final int SET_CENTER_CHUNK = id("set_chunk_cache_center", 0x60);
    public static final int SET_CHUNK_RADIUS = id("set_chunk_cache_radius", 0x61);
    public static final int SET_CURSOR_ITEM = id("set_cursor_item", 0x62);
    public static final int SET_DEFAULT_SPAWN_POSITION = id("set_default_spawn_position", 0x63);
    public static final int SET_ENTITY_DATA = id("set_entity_data", 0x65);
    public static final int SET_ENTITY_LINK = id("set_entity_link", 0x66);
    public static final int SET_ENTITY_MOTION = id("set_entity_motion", 0x67);
    public static final int SET_EQUIPMENT = id("set_equipment", 0x68);
    public static final int SET_EXPERIENCE = id("set_experience", 0x69);
    /** float health, VarInt food, float saturation. */
    public static final int SET_HEALTH = id("set_health", 0x6A);
    public static final int SET_HELD_SLOT = id("set_held_slot", 0x6B);
    public static final int SET_PASSENGERS = id("set_passengers", 0x6D);
    public static final int SET_PLAYER_INVENTORY = id("set_player_inventory", 0x6E);
    public static final int SET_SIMULATION_DISTANCE = id("set_simulation_distance", 0x71);
    public static final int SET_TIME = id("set_time", 0x73);
    public static final int SOUND = id("sound", 0x77);
    public static final int SOUND_ENTITY = id("sound_entity", 0x76);
    public static final int START_CONFIGURATION = id("start_configuration", 0x78);
    public static final int SWING_ANIMATION = id("swing_animation", 0x7B);
    public static final int SYSTEM_CHAT = id("system_chat", 0x7C);
    public static final int TAKE_ITEM_ENTITY = id("take_item_entity", 0x7F);
    public static final int TELEPORT_ENTITY = id("teleport_entity", 0x80);
    public static final int TICKING_STATE = id("ticking_state", 0x82);
    public static final int TRANSFER = id("transfer", 0x84);
    public static final int UPDATE_ATTRIBUTES = id("update_attributes", 0x86);
    public static final int UPDATE_MOB_EFFECT = id("update_mob_effect", 0x87);
    public static final int UPDATE_RECIPES = id("update_recipes", 0x88);
    public static final int UPDATE_TAGS = id("update_tags", 0x89);

    // -------------------------------------------------------- play serverbound --

    public static final int SB_ACCEPT_TELEPORTATION = id("accept_teleportation", 0x00);
    /** attack: VarInt entity id. This is the melee/left-click packet. */
    public static final int SB_ATTACK = id("attack", 0x01);
    public static final int SB_CHAT_ACK = id("chat_ack", 0x06);
    public static final int SB_CHAT_COMMAND = id("chat_command", 0x07);
    public static final int SB_CHAT_COMMAND_SIGNED = id("chat_command_signed", 0x08);
    public static final int SB_CHAT = id("chat", 0x09);
    public static final int SB_CHAT_SESSION_UPDATE = id("chat_session_update", 0x0A);
    public static final int SB_CHUNK_BATCH_RECEIVED = id("chunk_batch_received", 0x0B);
    public static final int SB_CLIENT_COMMAND = id("client_command", 0x0C);
    public static final int SB_CLIENT_TICK_END = id("client_tick_end", 0x0D);
    public static final int SB_CLIENT_INFORMATION = id("client_information", 0x0E);
    public static final int SB_COMMAND_SUGGESTION = id("command_suggestion", 0x0F);
    public static final int SB_CONTAINER_BUTTON_CLICK = id("container_button_click", 0x11);
    public static final int SB_CONTAINER_CLICK = id("container_click", 0x12);
    public static final int SB_CONTAINER_CLOSE = id("container_close", 0x13);
    public static final int SB_CONTAINER_SLOT_STATE_CHANGED = id("container_slot_state_changed", 0x14);
    public static final int SB_CUSTOM_PAYLOAD = id("custom_payload", 0x16);
    public static final int SB_EDIT_BOOK = id("edit_book", 0x18);
    /** interact: VarInt entity id, VarInt type, [target vec for interact_at]. */
    public static final int SB_INTERACT = id("interact", 0x1A);
    public static final int SB_KEEP_ALIVE = id("keep_alive", 0x1C);
    public static final int SB_MOVE_PLAYER_POS = id("move_player_pos", 0x1E);
    public static final int SB_MOVE_PLAYER_POS_ROT = id("move_player_pos_rot", 0x1F);
    public static final int SB_MOVE_PLAYER_ROT = id("move_player_rot", 0x20);
    public static final int SB_MOVE_PLAYER_STATUS_ONLY = id("move_player_status_only", 0x21);
    public static final int SB_MOVE_VEHICLE = id("move_vehicle", 0x22);
    public static final int SB_PADDLE_BOAT = id("paddle_boat", 0x23);
    public static final int SB_PICK_ITEM_FROM_BLOCK = id("pick_item_from_block", 0x24);
    public static final int SB_PICK_ITEM_FROM_ENTITY = id("pick_item_from_entity", 0x25);
    public static final int SB_PLACE_RECIPE = id("place_recipe", 0x27);
    public static final int SB_PLAYER_ABILITIES = id("player_abilities_sb", 0x28);
    public static final int SB_PLAYER_ACTION = id("player_action", 0x29);
    public static final int SB_PLAYER_COMMAND = id("player_command", 0x2A);
    public static final int SB_PLAYER_INPUT = id("player_input", 0x2B);
    public static final int SB_PLAYER_LOADED = id("player_loaded", 0x2C);
    /** punch: the arm swing / attack animation. Empty body. */
    public static final int SB_PUNCH = id("punch", 0x2E);
    public static final int SB_RECIPE_BOOK_CHANGE_SETTINGS = id("recipe_book_change_settings", 0x2F);
    public static final int SB_RECIPE_BOOK_SEEN_RECIPE = id("recipe_book_seen_recipe", 0x30);
    public static final int SB_RENAME_ITEM = id("rename_item", 0x31);
    public static final int SB_SELECT_TRADE = id("select_trade", 0x34);
    public static final int SB_SET_CARRIED_ITEM = id("set_carried_item", 0x36);
    public static final int SB_SET_CREATIVE_MODE_SLOT = id("set_creative_mode_slot", 0x39);
    public static final int SB_SIGN_UPDATE = id("sign_update", 0x3E);
    public static final int SB_SPECTATOR_ACTION = id("spectator_action", 0x3F);
    public static final int SB_TELEPORT_TO_ENTITY = id("teleport_to_entity", 0x40);
    public static final int SB_USE_ITEM_ON = id("use_item_on", 0x42);
    public static final int SB_USE_ITEM = id("use_item", 0x43);

    // -------------------------------------------------------------------------

    /** Every id this class knows, by symbolic name (for logging and probes). */
    public static Map<String, Integer> known() {
        return Collections.unmodifiableMap(KNOWN);
    }

    /** Name of a clientbound play packet, or null. */
    public static String clientboundName(int id) {
        return CLIENTBOUND_NAMES.get(id);
    }

    /** Name of a serverbound play packet, or null. */
    public static String serverboundName(int id) {
        return SERVERBOUND_NAMES.get(id);
    }

    static {
        // Filled from generated/reports/packets.json (play state only; the
        // other states are small enough to read from the constants above).
        put(true, 0x00, "bundle_delimiter"); put(true, 0x01, "add_entity");
        put(true, 0x02, "animate"); put(true, 0x03, "award_stats");
        put(true, 0x04, "block_changed_ack"); put(true, 0x05, "block_destruction");
        put(true, 0x06, "block_entity_data"); put(true, 0x07, "block_event");
        put(true, 0x08, "block_update"); put(true, 0x09, "boss_event");
        put(true, 0x0A, "change_difficulty"); put(true, 0x0B, "chunk_batch_finished");
        put(true, 0x0C, "chunk_batch_start"); put(true, 0x0D, "chunks_biomes");
        put(true, 0x0E, "clear_titles"); put(true, 0x0F, "command_suggestions");
        put(true, 0x10, "commands"); put(true, 0x11, "container_close");
        put(true, 0x12, "container_set_content"); put(true, 0x13, "container_set_data");
        put(true, 0x14, "container_set_slot"); put(true, 0x15, "cookie_request");
        put(true, 0x16, "cooldown"); put(true, 0x17, "custom_chat_completions");
        put(true, 0x18, "custom_payload"); put(true, 0x19, "damage_event");
        put(true, 0x1A, "debug/block_value"); put(true, 0x1B, "debug/chunk_value");
        put(true, 0x1C, "debug/entity_value"); put(true, 0x1D, "debug/event");
        put(true, 0x1E, "debug_sample"); put(true, 0x1F, "delete_chat");
        put(true, 0x20, "disconnect"); put(true, 0x21, "disguised_chat");
        put(true, 0x22, "entity_event"); put(true, 0x23, "entity_position_sync");
        put(true, 0x24, "explode"); put(true, 0x25, "add_transient_block");
        put(true, 0x26, "forget_level_chunk"); put(true, 0x27, "game_event");
        put(true, 0x28, "game_rule_values"); put(true, 0x29, "game_test_highlight_pos");
        put(true, 0x2A, "mount_screen_open"); put(true, 0x2B, "hurt_animation");
        put(true, 0x2C, "initialize_border"); put(true, 0x2D, "keep_alive");
        put(true, 0x2E, "level_chunk_with_light"); put(true, 0x2F, "level_event");
        put(true, 0x30, "level_particles"); put(true, 0x31, "light_update");
        put(true, 0x32, "login"); put(true, 0x33, "low_disk_space_warning");
        put(true, 0x34, "map_item_data"); put(true, 0x35, "merchant_offers");
        put(true, 0x36, "move_entity_pos"); put(true, 0x37, "move_entity_pos_rot");
        put(true, 0x38, "move_minecart_along_track"); put(true, 0x39, "move_entity_rot");
        put(true, 0x3A, "move_vehicle"); put(true, 0x3B, "open_book");
        put(true, 0x3C, "open_screen"); put(true, 0x3D, "open_sign_editor");
        put(true, 0x3E, "ping"); put(true, 0x3F, "pong_response");
        put(true, 0x40, "place_ghost_recipe"); put(true, 0x41, "player_abilities");
        put(true, 0x42, "player_chat"); put(true, 0x43, "player_combat_end");
        put(true, 0x44, "player_combat_enter"); put(true, 0x45, "player_combat_kill");
        put(true, 0x46, "player_info_remove"); put(true, 0x47, "player_info_update");
        put(true, 0x48, "player_look_at"); put(true, 0x49, "player_position");
        put(true, 0x4A, "player_rotation"); put(true, 0x4B, "recipe_book_add");
        put(true, 0x4C, "recipe_book_remove"); put(true, 0x4D, "recipe_book_settings");
        put(true, 0x4E, "remove_entities"); put(true, 0x4F, "remove_mob_effect");
        put(true, 0x50, "reset_score"); put(true, 0x51, "resource_pack_pop");
        put(true, 0x52, "resource_pack_push"); put(true, 0x53, "post_effects");
        put(true, 0x54, "respawn"); put(true, 0x55, "rotate_head");
        put(true, 0x56, "section_blocks_update"); put(true, 0x57, "select_advancements_tab");
        put(true, 0x58, "server_data"); put(true, 0x59, "set_action_bar_text");
        put(true, 0x5A, "set_border_center"); put(true, 0x5B, "set_border_lerp_size");
        put(true, 0x5C, "set_border_size"); put(true, 0x5D, "set_border_warning_delay");
        put(true, 0x5E, "set_border_warning_distance"); put(true, 0x5F, "set_camera");
        put(true, 0x60, "set_chunk_cache_center"); put(true, 0x61, "set_chunk_cache_radius");
        put(true, 0x62, "set_cursor_item"); put(true, 0x63, "set_default_spawn_position");
        put(true, 0x64, "set_display_objective"); put(true, 0x65, "set_entity_data");
        put(true, 0x66, "set_entity_link"); put(true, 0x67, "set_entity_motion");
        put(true, 0x68, "set_equipment"); put(true, 0x69, "set_experience");
        put(true, 0x6A, "set_health"); put(true, 0x6B, "set_held_slot");
        put(true, 0x6C, "set_objective"); put(true, 0x6D, "set_passengers");
        put(true, 0x6E, "set_player_inventory"); put(true, 0x6F, "set_player_team");
        put(true, 0x70, "set_score"); put(true, 0x71, "set_simulation_distance");
        put(true, 0x72, "set_subtitle_text"); put(true, 0x73, "set_time");
        put(true, 0x74, "set_title_text"); put(true, 0x75, "set_titles_animation");
        put(true, 0x76, "sound_entity"); put(true, 0x77, "sound");
        put(true, 0x78, "start_configuration"); put(true, 0x79, "stop_sound");
        put(true, 0x7A, "store_cookie"); put(true, 0x7B, "swing_animation");
        put(true, 0x7C, "system_chat"); put(true, 0x7D, "tab_list");
        put(true, 0x7E, "tag_query"); put(true, 0x7F, "take_item_entity");
        put(true, 0x80, "teleport_entity"); put(true, 0x81, "test_instance_block_status");
        put(true, 0x82, "ticking_state"); put(true, 0x83, "ticking_step");
        put(true, 0x84, "transfer"); put(true, 0x85, "update_advancements");
        put(true, 0x86, "update_attributes"); put(true, 0x87, "update_mob_effect");
        put(true, 0x88, "update_recipes"); put(true, 0x89, "update_tags");
        put(true, 0x8A, "projectile_power"); put(true, 0x8B, "custom_report_details");
        put(true, 0x8C, "server_links"); put(true, 0x8D, "waypoint");
        put(true, 0x8E, "clear_dialog"); put(true, 0x8F, "show_dialog");

        put(false, 0x00, "accept_teleportation"); put(false, 0x01, "attack");
        put(false, 0x02, "block_entity_tag_query"); put(false, 0x03, "bundle_item_selected");
        put(false, 0x04, "change_difficulty"); put(false, 0x05, "change_game_mode");
        put(false, 0x06, "chat_ack"); put(false, 0x07, "chat_command");
        put(false, 0x08, "chat_command_signed"); put(false, 0x09, "chat");
        put(false, 0x0A, "chat_session_update"); put(false, 0x0B, "chunk_batch_received");
        put(false, 0x0C, "client_command"); put(false, 0x0D, "client_tick_end");
        put(false, 0x0E, "client_information"); put(false, 0x0F, "command_suggestion");
        put(false, 0x10, "configuration_acknowledged"); put(false, 0x11, "container_button_click");
        put(false, 0x12, "container_click"); put(false, 0x13, "container_close");
        put(false, 0x14, "container_slot_state_changed"); put(false, 0x15, "cookie_response");
        put(false, 0x16, "custom_payload"); put(false, 0x17, "debug_subscription_request");
        put(false, 0x18, "edit_book"); put(false, 0x19, "entity_tag_query");
        put(false, 0x1A, "interact"); put(false, 0x1B, "jigsaw_generate");
        put(false, 0x1C, "keep_alive"); put(false, 0x1D, "lock_difficulty");
        put(false, 0x1E, "move_player_pos"); put(false, 0x1F, "move_player_pos_rot");
        put(false, 0x20, "move_player_rot"); put(false, 0x21, "move_player_status_only");
        put(false, 0x22, "move_vehicle"); put(false, 0x23, "paddle_boat");
        put(false, 0x24, "pick_item_from_block"); put(false, 0x25, "pick_item_from_entity");
        put(false, 0x26, "ping_request"); put(false, 0x27, "place_recipe");
        put(false, 0x28, "player_abilities"); put(false, 0x29, "player_action");
        put(false, 0x2A, "player_command"); put(false, 0x2B, "player_input");
        put(false, 0x2C, "player_loaded"); put(false, 0x2D, "pong");
        put(false, 0x2E, "punch"); put(false, 0x2F, "recipe_book_change_settings");
        put(false, 0x30, "recipe_book_seen_recipe"); put(false, 0x31, "rename_item");
        put(false, 0x32, "resource_pack"); put(false, 0x33, "seen_advancements");
        put(false, 0x34, "select_trade"); put(false, 0x35, "set_beacon");
        put(false, 0x36, "set_carried_item"); put(false, 0x37, "set_command_block");
        put(false, 0x38, "set_command_minecart"); put(false, 0x39, "set_creative_mode_slot");
        put(false, 0x3A, "set_game_rule"); put(false, 0x3B, "set_jigsaw_block");
        put(false, 0x3C, "set_structure_block"); put(false, 0x3D, "set_test_block");
        put(false, 0x3E, "sign_update"); put(false, 0x3F, "spectator_action");
        put(false, 0x40, "teleport_to_entity"); put(false, 0x41, "test_instance_block_action");
        put(false, 0x42, "use_item_on"); put(false, 0x43, "use_item");
        put(false, 0x44, "custom_click_action");
    }

    private static void put(boolean clientbound, int id, String name) {
        (clientbound ? CLIENTBOUND_NAMES : SERVERBOUND_NAMES).put(id, name);
    }
}
