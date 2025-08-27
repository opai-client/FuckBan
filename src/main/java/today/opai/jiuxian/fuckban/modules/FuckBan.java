package today.opai.jiuxian.fuckban.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.enums.EnumNotificationType;
import today.opai.api.features.ExtensionModule;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.TextValue;
import today.opai.api.interfaces.modules.values.BooleanValue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static today.opai.jiuxian.fuckban.ExampleExtension.openAPI;

public class FuckBan extends ExtensionModule implements EventHandler {
    public static FuckBan INSTANCE;

    private TextValue apiKeyValue;
    private BooleanValue chineseModeValue;
    private BooleanValue onlyHypixelValue;
    private BooleanValue testConnectionValue;
    private static final String CN_TOKEN = "CnToken"; 

    private int lastWatchdogTotal = -1;
    private int lastStaffTotal = -1;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private boolean canNotify;
    private static volatile long lastNotificationMs = 0L;
    private volatile long nextScheduledAtMs = 0L;

    public FuckBan() {
        super("FuckBan", "Monitor Hypixel bans and report deltas every minute", EnumModuleCategory.MISC);
        setEventHandler(this);
        ensureValues();
        INSTANCE = this;
    }

    @Override
    public void onEnabled() {
        super.onEnabled();
        ensureValues();
        canNotify = false;
        startStartupProbe();
        log("Module enabled, starting connectivity check...");
    }

    @Override
    public void onDisabled() {
        super.onDisabled();
        stopScheduler();
        log("Module disabled");
    }

    private void ensureValues(){
        try{
            if(openAPI != null){
                if(apiKeyValue == null){
                    apiKeyValue = openAPI.getValueManager().createInput("API Key", "");
                }
                if(chineseModeValue == null){
                    chineseModeValue = openAPI.getValueManager().createBoolean("Chinese Mode", false);
                }
                if(onlyHypixelValue == null){
                    onlyHypixelValue = openAPI.getValueManager().createBoolean("[bad]only Hypixel", false);
                    try{
                        onlyHypixelValue.setValueCallback(new java.util.function.Consumer<Boolean>() {
                            @Override
                            public void accept(Boolean v) {
                                // 重启采集计划，并同步修正倒计时
                                boolean shouldRun = !Boolean.TRUE.equals(v) || isOnHypixel();
                                stopScheduler();
                                if(shouldRun){
                                    startScheduler();
                                    nextScheduledAtMs = System.currentTimeMillis() + 60_000L;
                                }else{
                                    nextScheduledAtMs = System.currentTimeMillis();
                                }
                            }
                        });
                    }catch(Throwable ignored){}
                }
                if(testConnectionValue == null){
                    testConnectionValue = openAPI.getValueManager().createBoolean("Test Connection", false);
                    try{
                        testConnectionValue.setValueCallback(new java.util.function.Consumer<Boolean>() {
                            @Override
                            public void accept(Boolean v) {
                                if(Boolean.TRUE.equals(v)){
                                    runTestConnectionAsync();
                                    try{ testConnectionValue.setValue(false);}catch(Throwable ignored){}
                                }
                            }
                        });
                    }catch(Throwable ignored){}
                }
                boolean needAdd = false;
                if(!getValues().contains(apiKeyValue)) needAdd = true;
                if(!getValues().contains(chineseModeValue)) needAdd = true;
                if(!getValues().contains(onlyHypixelValue)) needAdd = true;
                if(!getValues().contains(testConnectionValue)) needAdd = true;
                if(needAdd){
                    addValues(apiKeyValue, chineseModeValue, onlyHypixelValue, testConnectionValue);
                }
            }
        }catch(Throwable ignored){
        }
    }

    private void startStartupProbe(){
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = probeConnectivity();
                if(ok){
                    display("网络已连接，开始监控");
                    startScheduler();
                }else{
                    display("无法连接服务，模块将关闭");
                    try{ setEnabled(false); }catch(Throwable ignored){}
                }
            }
        }, "FuckBan-Startup");
        t.setDaemon(true);
        t.start();
    }

    private void runTestConnectionAsync(){
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = probeConnectivity();
                display(ok ? "连接测试成功" : "连接测试失败");
            }
        }, "FuckBan-TestConn");
        t.setDaemon(true);
        t.start();
    }

    private boolean isOnHypixel(){
        try{
            if(openAPI == null || openAPI.getWorld() == null) return false;
            String ip = null;
            try{
                ip = (String) openAPI.getWorld().getClass().getMethod("getServerIP").invoke(openAPI.getWorld());
            }catch(Throwable ignored){}
            if(ip == null) return false;
            ip = ip.toLowerCase();
            return ip.contains("hypixel.net") || ip.contains("hypixel.io") || ip.contains("mc.hypixel.net");
        }catch(Throwable ignored){}
        return false;
    }

    private boolean probeConnectivity(){
        boolean cnMode = false;
        try{ cnMode = chineseModeValue != null && Boolean.TRUE.equals(chineseModeValue.getValue()); }catch(Throwable ignored){}
        String healthUrl = cnMode ? "http://127.0.0.1:5201/health" : "https://api.hypixel.net/";
        for(int i=0;i<3;i++){
            try{
                HttpURLConnection conn = (HttpURLConnection) new URL(healthUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if(code >= 200 && code < 400){
                    return true;
                }
            }catch(Exception ignored){}
            try{ Thread.sleep(400L); }catch(InterruptedException ignored){}
        }
        return false;
    }

    private void startScheduler(){
        try{
            if(scheduler == null || scheduler.isShutdown()){
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "FuckBan-Fetcher");
                    t.setDaemon(true);
                    return t;
                });
            }
            if(scheduledTask == null || scheduledTask.isCancelled()){
                nextScheduledAtMs = System.currentTimeMillis() + 60_000L;
                scheduledTask = scheduler.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        nextScheduledAtMs = System.currentTimeMillis() + 60_000L;
                        fetchAndReport();
                    }
                }, 0, 60, TimeUnit.SECONDS);
            }
        }catch(Throwable ignored){
        }
    }

    private void stopScheduler(){
        try{
            if(scheduledTask != null){
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
            if(scheduler != null){
                scheduler.shutdownNow();
                scheduler = null;
            }
        }catch(Throwable ignored){
        }
    }

    @Override
    public void onPlayerUpdate() {
        try {
            boolean ready = openAPI != null && !openAPI.isNull();
            if (ready != canNotify) {
                canNotify = ready;
            }
        } catch (Throwable ignored) {}

        try{
            String mode = (chineseModeValue != null && Boolean.TRUE.equals(chineseModeValue.getValue())) ? "CN" : "GL";
            long remain = Math.max(0L, nextScheduledAtMs - System.currentTimeMillis());
            long sec = (long) Math.ceil(remain / 1000.0);
            setSuffix(mode + " " + sec + "s");
        }catch(Throwable ignored){}
    }


    private void fetchAndReport() {

        String apiKey = apiKeyValue == null ? "" : apiKeyValue.getValue();
        boolean cnMode = false;
        try{ cnMode = chineseModeValue != null && Boolean.TRUE.equals(chineseModeValue.getValue()); }catch(Throwable ignored){}
        try {
            boolean onlyHypixel = onlyHypixelValue != null && Boolean.TRUE.equals(onlyHypixelValue.getValue());
            if (onlyHypixel && !isOnHypixel()) {
                return;
            }
        } catch (Throwable ignored) {}
        if (!cnMode && (apiKey == null || apiKey.isEmpty())) {
            display("API Key is required in Global Mode");
            log("API Key missing in Global Mode");
            return;
        }

        try {
            String url;
            if(cnMode){
                String base = "http://127.0.0.1:5201/watchdogstats";
                String token = CN_TOKEN;
                if(token == null || token.isEmpty()){
                    log("Chinese Mode 启用但未配置内置 Token，已跳过请求");
                    return;
                }
                StringBuilder sbUrl = new StringBuilder(base).append("?token=").append(encode(token));
                if(apiKey != null && !apiKey.isEmpty()){
                    sbUrl.append("&key=").append(encode(apiKey));
                }
                url = sbUrl.toString();
            }else{
                url = "https://api.hypixel.net/watchdogstats?key=" + encode(apiKey);
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int status = conn.getResponseCode();
            if (status != 200) {
                log("HTTP status: " + status + ", url=" + url);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                JsonObject json = new JsonParser().parse(sb.toString()).getAsJsonObject();
                if (!json.has("success") || !json.get("success").getAsBoolean()) {
                    log("Upstream not success, url=" + url);
                    return;
                }

                int watchdogTotal = json.has("watchdog_total") ? json.get("watchdog_total").getAsInt() : -1;
                int staffTotal = json.has("staff_total") ? json.get("staff_total").getAsInt() : -1;

                if (watchdogTotal < 0 || staffTotal < 0) {
                    log("响应缺少 total 字段");
                    return;
                }

                if (lastWatchdogTotal == -1 || lastStaffTotal == -1) {
                    lastWatchdogTotal = watchdogTotal;
                    lastStaffTotal = staffTotal;
                    return;
                }

                int newWatchdog = watchdogTotal - lastWatchdogTotal;
                int newStaff = staffTotal - lastStaffTotal;

                if (newWatchdog > 0 || newStaff > 0) {
                    String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                    String message = buildColoredMessage(time, newStaff, newWatchdog);
                    display(message);
                    if(newStaff > 0){
                        sendStaffAlert();
                    }
                }

                lastWatchdogTotal = watchdogTotal;
                lastStaffTotal = staffTotal;
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            log("请求异常: " + ex.getClass().getSimpleName());
        }
    }

    private String buildColoredMessage(String time, int staffDelta, int watchdogDelta){
        String staffColor = staffDelta > 2 ? "§c" : "§a"; // >2 红色，否则绿色
        String wdColor = watchdogDelta > 2 ? "§c" : "§a";
        return "[" + time + "]" + staffColor + "Staff:" + staffDelta + "人" +
                " §7" + wdColor + "Watchdog:" + watchdogDelta + "人" + "§r";
    }

    private void display(String message){
        if(message == null || message.isEmpty()) return;
        // MC 日志（ASCII 安全）
        log(asciiSafe(message));
        // 灵动岛（通知），仅在进入世界后提示
        if(canNotify && openAPI != null){
            try{
                long now = System.currentTimeMillis();
                long minGap = 400L; // 与其他通知做简单错峰，近似“下面一条”
                long delay = Math.max(0L, (lastNotificationMs + minGap) - now);

                final String msgToShow = delay > 0 ? ("\n" + message) : message;

                if (delay == 0L || scheduler == null) {
                    openAPI.popNotification(EnumNotificationType.INFO, "FuckBan", msgToShow, 3000L);
                } else {
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try { openAPI.popNotification(EnumNotificationType.INFO, "FuckBan", msgToShow, 3000L); } catch (Throwable ignored) {}
                        }
                    }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                lastNotificationMs = now + delay;
            }catch(Throwable ignored){
            }
        }
    }

    private void sendStaffAlert(){
        try{
            if(openAPI != null){
                openAPI.printMessage("§c§lStaff来撞人了");
            }
        }catch(Throwable ignored){}
    }

    private void log(String text){
        try{
            System.out.println("[FuckBan] " + text);
        }catch(Throwable ignored){
        }
    }

    private String asciiSafe(String s){
        if(s == null) return "";
        String noColor = s.replace('§',' ');
        StringBuilder sb = new StringBuilder(noColor.length());
        for(char c : noColor.toCharArray()){
            if(c >= 32 && c <= 126){
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String encode(String s){
        try{
            return java.net.URLEncoder.encode(s, "UTF-8");
        }catch(Exception ignored){}
        return s;
    }
}



