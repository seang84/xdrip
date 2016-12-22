package com.eveningoutpost.dexdrip.Models;

import android.provider.BaseColumns;

import com.activeandroid.Model;
import com.activeandroid.annotation.Column;
import com.activeandroid.annotation.Table;
import com.activeandroid.query.Delete;
import com.activeandroid.query.Select;
import com.activeandroid.util.SQLiteUtils;
import com.eveningoutpost.dexdrip.GlucoseMeter.GlucoseReadingRx;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.messages.BloodTestMessage;
import com.eveningoutpost.dexdrip.messages.BloodTestMultiMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.squareup.wire.Wire;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by jamorham on 11/12/2016.
 */

@Table(name = "BloodTest", id = BaseColumns._ID)
public class BloodTest extends Model {

    public static final long STATE_VALID = 1 << 0;
    public static final long STATE_CALIBRATION = 1 << 1;
    public static final long STATE_NOTE = 1 << 2;
    public static final long STATE_UNDONE = 1 << 3;
    public static final long STATE_OVERWRITTEN = 1 << 4;


    private static boolean patched = false;
    private final static String TAG = "BloodTest";
    private final static boolean d = true;

    @Expose
    @Column(name = "timestamp", unique = true, onUniqueConflicts = Column.ConflictAction.IGNORE)
    public long timestamp;

    @Expose
    @Column(name = "mgdl")
    public double mgdl;

    @Expose
    @Column(name = "created_timestamp")
    public long created_timestamp;

    @Expose
    @Column(name = "state")
    public long state; // bitfield

    @Expose
    @Column(name = "source")
    public String source;

    @Expose
    @Column(name = "uuid", unique = true, onUniqueConflicts = Column.ConflictAction.IGNORE)
    public String uuid;


    public GlucoseReadingRx glucoseReadingRx;

    // patches and saves
    public Long saveit() {
        fixUpTable();
        return save();
    }

    public void addState(long flag) {
        state |= flag;
        save();
    }

    public void removeState(long flag) {
        state &= ~flag;
        save();
    }

    public String toS() {
        final Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .create();
        return gson.toJson(this);
    }

    private BloodTestMessage toMessageNative() {
        return new BloodTestMessage.Builder()
                .timestamp(timestamp)
                .mgdl(mgdl)
                .created_timestamp(created_timestamp)
                .state(state)
                .source(source)
                .uuid(uuid)
                .build();
    }

    public byte[] toMessage() {
        final List<BloodTest> btl = new ArrayList<>();
        btl.add(this);
        return toMultiMessage(btl);
    }


    // static methods
    private static final long CLOSEST_READING_MS = 30000; // 30 seconds

    public static BloodTest create(long timestamp_ms, double mgdl, String source) {

        if ((timestamp_ms == 0) || (mgdl == 0)) {
            UserError.Log.e(TAG, "Either timestamp or mgdl is zero - cannot create reading");
            return null;
        }

        final long now = JoH.tsl();
        if (timestamp_ms > now) {
            if ((timestamp_ms - now) > 600000) {
                UserError.Log.wtf(TAG, "Timestamp is > 10 minutes in the future! Something is wrong: " + JoH.dateTimeText(timestamp_ms));
                return null;
            }
            timestamp_ms = now; // force to now if it showed up to 10 mins in the future
        }

        final BloodTest match = getForPreciseTimestamp(timestamp_ms, CLOSEST_READING_MS);
        if (match == null) {
            final BloodTest bt = new BloodTest();
            bt.timestamp = timestamp_ms;
            bt.mgdl = mgdl;
            bt.uuid = UUID.randomUUID().toString();
            bt.created_timestamp = JoH.tsl();
            bt.state = STATE_VALID;
            bt.source = source;
            bt.saveit();
            return bt;
        } else {
            UserError.Log.d(TAG, "Not creating new reading as timestamp is too close");
        }
        return null;
    }

    public static BloodTest last() {
        final List<BloodTest> btl = last(1);
        if ((btl != null) && (btl.size() > 0)) {
            return btl.get(0);
        } else {
            return null;
        }
    }

    public static List<BloodTest> last(int num) {
        try {
            return new Select()
                    .from(BloodTest.class)
                    .orderBy("timestamp desc")
                    .limit(num)
                    .execute();
        } catch (android.database.sqlite.SQLiteException e) {
            fixUpTable();
            return null;
        }
    }

    public static BloodTest byUUID(String uuid) {
        if (uuid == null) return null;
        try {
            return new Select()
                    .from(BloodTest.class)
                    .where("uuid = ?", uuid)
                    .executeSingle();
        } catch (android.database.sqlite.SQLiteException e) {
            fixUpTable();
            return null;
        }
    }

    public static byte[] toMultiMessage(List<BloodTest> btl) {
        if (btl == null) return null;
        final List<BloodTestMessage> BloodTestMessageList = new ArrayList<>();
        for (BloodTest bt : btl) {
            BloodTestMessageList.add(bt.toMessageNative());
        }
        return BloodTestMultiMessage.ADAPTER.encode(new BloodTestMultiMessage(BloodTestMessageList));
    }

    private static void processFromMessage(BloodTestMessage btm) {
        if ((btm != null) && (btm.uuid != null) && (btm.uuid.length() == 36)) {
            BloodTest bt = byUUID(btm.uuid);
            if (bt == null) {
                bt = getForPreciseTimestamp(Wire.get(btm.timestamp, BloodTestMessage.DEFAULT_TIMESTAMP), CLOSEST_READING_MS);
                if (bt != null) {
                    UserError.Log.wtf(TAG, "Error matches a different uuid with the same timestamp: " + bt.uuid + " vs " + btm.uuid + " skipping!");
                    return;
                }
                bt = new BloodTest();
            }
            bt.timestamp = Wire.get(btm.timestamp, BloodTestMessage.DEFAULT_TIMESTAMP);
            bt.mgdl = Wire.get(btm.mgdl, BloodTestMessage.DEFAULT_MGDL);
            bt.created_timestamp = Wire.get(btm.created_timestamp, BloodTestMessage.DEFAULT_CREATED_TIMESTAMP);
            bt.state = Wire.get(btm.state, BloodTestMessage.DEFAULT_STATE);
            bt.source = Wire.get(btm.source, BloodTestMessage.DEFAULT_SOURCE);
            bt.uuid = btm.uuid;
            bt.saveit(); // de-dupe by uuid
        } else {
            UserError.Log.wtf(TAG, "processFromMessage uuid is null or invalid");
        }
    }

    public static void processFromMultiMessage(byte[] payload) {
        try {
            final BloodTestMultiMessage btmm = BloodTestMultiMessage.ADAPTER.decode(payload);
            if ((btmm != null) && (btmm.bloodtest_message != null)) {
                for (BloodTestMessage btm : btmm.bloodtest_message) {
                    processFromMessage(btm);
                }
                Home.staticRefreshBGCharts();
            }
        } catch (IOException e) {
            UserError.Log.e(TAG, "exception processFromMessage: ", e);
        }
    }

    public static BloodTest fromJSON(String json) {
        if ((json == null) || (json.length() == 0)) {
            UserError.Log.d(TAG, "Empty json received in bloodtest fromJson");
            return null;
        }
        try {
            UserError.Log.d(TAG, "Processing incoming json: " + json);
            return new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson(json, BloodTest.class);
        } catch (Exception e) {
            UserError.Log.d(TAG, "Got exception parsing bloodtest json: " + e.toString());
            Home.toaststaticnext("Error on Bloodtest sync, probably decryption key mismatch");
            return null;
        }
    }

    public static BloodTest getForPreciseTimestamp(long timestamp, long precision) {
        BloodTest bloodTest = new Select()
                .from(BloodTest.class)
                .where("timestamp <= ?", (timestamp + precision))
                .where("timestamp >= ?", (timestamp - precision))
                .orderBy("abs(timestamp - " + timestamp + ") asc")
                .executeSingle();
        if ((bloodTest != null) && (Math.abs(bloodTest.timestamp - timestamp) < precision)) {
            return bloodTest;
        }
        return null;
    }

    public static List<BloodTest> latestForGraph(int number, double startTime) {
        return latestForGraph(number, (long) startTime, Long.MAX_VALUE);
    }

    public static List<BloodTest> latestForGraph(int number, long startTime) {
        return latestForGraph(number, startTime, Long.MAX_VALUE);
    }

    public static List<BloodTest> latestForGraph(int number, long startTime, long endTime) {
        try {
            return new Select()
                    .from(BloodTest.class)
                    .where("state & ? != 0", BloodTest.STATE_VALID)
                    .where("timestamp >= " + Math.max(startTime, 0))
                    .where("timestamp <= " + endTime)
                    .orderBy("timestamp asc") // warn asc!
                    .limit(number)
                    .execute();
        } catch (android.database.sqlite.SQLiteException e) {
            fixUpTable();
            return new ArrayList<>();
        }
    }


    public static List<BloodTest> cleanup(int retention_days) {
        return new Delete()
                .from(BloodTest.class)
                .where("timestamp < ?", JoH.tsl() - (retention_days * 86400000L))
                .execute();
    }

    // create the table ourselves without worrying about model versioning and downgrading
    private static void fixUpTable() {
        if (patched) return;
        String[] patchup = {
                "CREATE TABLE BloodTest (_id INTEGER PRIMARY KEY AUTOINCREMENT);",
                "ALTER TABLE BloodTest ADD COLUMN timestamp INTEGER;",
                "ALTER TABLE BloodTest ADD COLUMN created_timestamp INTEGER;",
                "ALTER TABLE BloodTest ADD COLUMN state INTEGER;",
                "ALTER TABLE BloodTest ADD COLUMN mgdl REAL;",
                "ALTER TABLE BloodTest ADD COLUMN source TEXT;",
                "ALTER TABLE BloodTest ADD COLUMN uuid TEXT;",
                "CREATE UNIQUE INDEX index_Bloodtest_uuid on BloodTest(uuid);",
                "CREATE UNIQUE INDEX index_Bloodtest_timestamp on BloodTest(timestamp);",
                "CREATE INDEX index_Bloodtest_created_timestamp on BloodTest(created_timestamp);",
                "CREATE INDEX index_Bloodtest_state on BloodTest(state);"};

        for (String patch : patchup) {
            try {
                SQLiteUtils.execSql(patch);
                //  UserError.Log.e(TAG, "Processed patch should not have succeeded!!: " + patch);
            } catch (Exception e) {
                //  UserError.Log.d(TAG, "Patch: " + patch + " generated exception as it should: " + e.toString());
            }
        }
        patched = true;
    }
}
