package br.com.redesurftank.havalshisuku.services;

import android.os.IBinder;

interface ICarPlayClusterBridge {
    void offerEndpoint(IBinder endpoint, in byte[] token, long generation);
}
