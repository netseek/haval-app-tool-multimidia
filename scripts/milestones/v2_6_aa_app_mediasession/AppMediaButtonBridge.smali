.class public Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
.super Landroid/media/session/MediaSession$Callback;
.implements Landroid/content/ServiceConnection;
.source "AppMediaButtonBridge.java"

# Injected by Impulse aa-patches v2.6 (App side): give Android Auto its own framework MediaSession,
# hosted by AapActivity (claimed on onResume) so AA wins the OS media-button session over a paused
# local app (e.g. YouTube). Media buttons land in the callback and are routed to AA's projection via
# the projection Service's LinkCommand AIDL (bind + raw transact: next 0x18 / prev 0x19 / play 0x1c /
# pause 0x1d) -- the same proven path Impulse used for PREVIOUS.

.field private static sSession:Landroid/media/session/MediaSession;
.field private static sSelf:Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
.field private static sCtx:Landroid/content/Context;
.field private static sLinkBinder:Landroid/os/IBinder;


.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/media/session/MediaSession$Callback;-><init>()V
    return-void
.end method

.method public static install(Landroid/content/Context;)V
    .locals 3
    invoke-virtual {p0}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    sput-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sCtx:Landroid/content/Context;

    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;
    if-eqz v0, :create
    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->ensureBound()V
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
    sput-object v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSelf:Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
    invoke-virtual {v0, v1}, Landroid/media/session/MediaSession;->setCallback(Landroid/media/session/MediaSession$Callback;)V

    sput-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSession:Landroid/media/session/MediaSession;

    const-string v1, "AAMediaBtn"
    const-string v2, "App MediaSession installed"
    invoke-static {v1, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->ensureBound()V
    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->claim()V
    return-void
.end method

.method public static ensureBound()V
    .locals 7
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sLinkBinder:Landroid/os/IBinder;
    if-eqz v0, :needbind
    invoke-interface {v0}, Landroid/os/IBinder;->isBinderAlive()Z
    move-result v1
    if-eqz v1, :needbind
    return-void

    :needbind
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sCtx:Landroid/content/Context;
    if-eqz v0, :done
    sget-object v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSelf:Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
    if-nez v1, :haveself
    new-instance v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;
    invoke-direct {v1}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;-><init>()V
    sput-object v1, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sSelf:Lcom/ts/androidauto/app/display/AppMediaButtonBridge;

    :haveself
    new-instance v2, Landroid/content/Intent;
    const-string v3, "com.ts.androidauto.action.AndroidAutoService"
    invoke-direct {v2, v3}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    new-instance v3, Landroid/content/ComponentName;
    const-string v4, "com.ts.androidauto.projectionservice"
    const-string v5, "com.ts.androidauto.projectionservice.AndroidAutoService"
    invoke-direct {v3, v4, v5}, Landroid/content/ComponentName;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    invoke-virtual {v2, v3}, Landroid/content/Intent;->setComponent(Landroid/content/ComponentName;)Landroid/content/Intent;
    const/4 v6, 0x1
    :try_start_0
    invoke-virtual {v0, v2, v1, v6}, Landroid/content/Context;->bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z
    const-string v3, "AAMediaBtn"
    const-string v4, "ensureBound: LinkCommand bindService requested"
    invoke-static {v3, v4}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
    goto :done
    :catch_0
    move-exception v0
    const-string v1, "AAMediaBtn"
    const-string v2, "ensureBound: bindService failed"
    invoke-static {v1, v2, v0}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    :done
    return-void
.end method

.method public onServiceConnected(Landroid/content/ComponentName;Landroid/os/IBinder;)V
    .locals 2
    sput-object p2, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sLinkBinder:Landroid/os/IBinder;
    const-string v0, "AAMediaBtn"
    const-string v1, "LinkCommand connected"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method

.method public onServiceDisconnected(Landroid/content/ComponentName;)V
    .locals 1
    const/4 v0, 0x0
    sput-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sLinkBinder:Landroid/os/IBinder;
    return-void
.end method

.method private static linkTransact(I)V
    .locals 5
    sget-object v0, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sLinkBinder:Landroid/os/IBinder;
    if-eqz v0, :rebind
    invoke-interface {v0}, Landroid/os/IBinder;->isBinderAlive()Z
    move-result v1
    if-nez v1, :ok
    :rebind
    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->ensureBound()V
    return-void

    :ok
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;
    move-result-object v1
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;
    move-result-object v2
    :try_start_0
    const-string v3, "com.ts.androidauto.sdk.aidl.LinkCommand"
    invoke-virtual {v1, v3}, Landroid/os/Parcel;->writeInterfaceToken(Ljava/lang/String;)V
    const/4 v3, 0x0
    invoke-interface {v0, p0, v1, v2, v3}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    invoke-virtual {v2}, Landroid/os/Parcel;->readException()V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
    invoke-virtual {v2}, Landroid/os/Parcel;->recycle()V
    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V
    return-void

    :catch_0
    move-exception v3
    const/4 v4, 0x0
    sput-object v4, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->sLinkBinder:Landroid/os/IBinder;
    const-string v4, "AAMediaBtn"
    const-string v0, "linkTransact failed"
    invoke-static {v4, v0, v3}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    invoke-virtual {v2}, Landroid/os/Parcel;->recycle()V
    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V
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

    invoke-static {}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->ensureBound()V

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

.method public onSkipToNext()V
    .locals 2
    const-string v0, "AAMediaBtn"
    const-string v1, "App onSkipToNext -> LinkCommand.next"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    const/16 v0, 0x18
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->linkTransact(I)V
    return-void
.end method

.method public onSkipToPrevious()V
    .locals 2
    const-string v0, "AAMediaBtn"
    const-string v1, "App onSkipToPrevious -> LinkCommand.previous"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    const/16 v0, 0x19
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->linkTransact(I)V
    return-void
.end method

.method public onPlay()V
    .locals 2
    const-string v0, "AAMediaBtn"
    const-string v1, "App onPlay -> LinkCommand.play"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    const/16 v0, 0x1c
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->linkTransact(I)V
    return-void
.end method

.method public onPause()V
    .locals 2
    const-string v0, "AAMediaBtn"
    const-string v1, "App onPause -> LinkCommand.pause"
    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    const/16 v0, 0x1d
    invoke-static {v0}, Lcom/ts/androidauto/app/display/AppMediaButtonBridge;->linkTransact(I)V
    return-void
.end method
