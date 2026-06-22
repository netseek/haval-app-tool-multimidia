.class public Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
.super Landroid/media/session/MediaSession$Callback;
.source "AppMediaButtonBridge.java"

# Injected by Impulse aa-patches v2.6 (App side): give Android Auto its own framework MediaSession,
# hosted by AapActivity so it is claimed on onResume (when AA comes foreground after another app),
# making AA win the OS media-button session over a paused local app (e.g. YouTube). Media buttons
# land here and are routed to AA's projection via AndroidAutoRemoteUiManager.sendKeyEvent (the same
# path HardKeyModel uses), so YouTube no longer double-reacts.

.field private static sSession:Landroid/media/session/MediaSession;


.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/media/session/MediaSession$Callback;-><init>()V
    return-void
.end method

.method public static install(Landroid/content/Context;)V
    .locals 6
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    if-eqz v0, :create
    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->claim()V
    return-void

    :create
    new-instance v0, Landroid/media/session/MediaSession;
    const-string v1, "HavalAaApp"
    invoke-direct {v0, p0, v1}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;)V

    const/4 v1, 0x3
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setFlags(I)V

    new-instance v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
    invoke-direct {v1}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;-><init>()V
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setCallback(Landroid/media/session/MediaSession$Callback;)V

    sput-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;

    const-string v1, "AAMediaBtn"
    const-string v2, "App MediaSession installed"
    invoke-static {v1, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->claim()V
    return-void
.end method

.method public static claim()V
    .locals 6
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    if-eqz v0, :done

    new-instance v1, Landroid/media/session/PlaybackState$Builder;
    invoke-direct {v1}, Landroid/media/session/PlaybackState$Builder;-><init>()V
    const-wide/16 v2, 0x236
    invoke-virtual {v1, v2, v3}, Landroid/media/session/PlaybackState$Builder;->setActions(J)Landroid/media/session/PlaybackState$Builder;
    move-result-object v1
    const/4 v2, 0x3
    const-wide/16 v3, 0x0
    const/high16 v5, 0x3f800000    # 1.0f
    invoke-virtual {v1, v2, v3, v4, v5}, Landroid/media/session/PlaybackState$Builder;->setState(IJF)Landroid/media/session/PlaybackState$Builder;
    move-result-object v1
    invoke-virtual {v1}, Landroid/media/session/PlaybackState$Builder;->build()Landroid/media/session/PlaybackState;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setPlaybackState(Landroid/media/session/PlaybackState;)V

    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setActive(Z)V

    const-string v1, "AAMediaBtn"
    const-string v2, "App MediaSession claimed (active+PLAYING)"
    invoke-static {v1, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :done
    return-void
.end method

.method public static deactivate()V
    .locals 2
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    if-eqz v0, :done
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setActive(Z)V
    const-string v0, "AAMediaBtn"
    const-string v1, "App MediaSession deactivated (released button)"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :done
    return-void
.end method

.method public static release()V
    .locals 2
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    if-eqz v0, :done
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setActive(Z)V
    invoke-virtual {v0}, Landroid/media/session/MediaSession;->release()V
    const/4 v1, 0x0
    sput-object v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    :done
    return-void
.end method

.method private static sendHk(Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;)V
    .locals 3
    invoke-static {}, Lcom/ts/androidauto/app/manager/AndroidAutoRemoteUiManager;->getInstance()Lcom/ts/androidauto/app/manager/AndroidAutoRemoteUiManager;
    move-result-object v0
    if-eqz v0, :done
    invoke-virtual {p0}, Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;->ordinal()I
    move-result v1
    const/4 v2, 0x0
    invoke-virtual {v0, v1, v2}, Lcom/ts/androidauto/app/manager/AndroidAutoRemoteUiManager;->sendKeyEvent(II)V
    const/4 v2, 0x1
    invoke-virtual {v0, v1, v2}, Lcom/ts/androidauto/app/manager/AndroidAutoRemoteUiManager;->sendKeyEvent(II)V
    :done
    return-void
.end method

.method public onSkipToNext()V
    .locals 1
    const-string v0, "AAMediaBtn"
    const-string p0, "App onSkipToNext -> AAP MEDIA_NEXT"
    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    sget-object v0, Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;->AAP_KEYCODE_MEDIA_NEXT:Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sendHk(Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;)V
    return-void
.end method

.method public onSkipToPrevious()V
    .locals 1
    const-string v0, "AAMediaBtn"
    const-string p0, "App onSkipToPrevious -> AAP MEDIA_PREVIOUS"
    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    sget-object v0, Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;->AAP_KEYCODE_MEDIA_PREVIOUS:Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sendHk(Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;)V
    return-void
.end method

.method public onPlay()V
    .locals 1
    sget-object v0, Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;->AAP_KEYCODE_MEDIA_PLAY_PAUSE:Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sendHk(Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;)V
    return-void
.end method

.method public onPause()V
    .locals 1
    sget-object v0, Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;->AAP_KEYCODE_MEDIA_PLAY_PAUSE:Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sendHk(Lcom/ts/androidauto/sdk/common/VehicleConst$AapHardkeyEvent;)V
    return-void
.end method
