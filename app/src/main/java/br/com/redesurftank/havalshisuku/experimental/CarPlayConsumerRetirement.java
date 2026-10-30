package br.com.redesurftank.havalshisuku.experimental;

final class CarPlayConsumerRetirement {
    private final Runnable releaseSurface;
    private final Runnable releaseTexture;
    private final Runnable onFinished;
    private boolean producerStopped;
    private boolean detached;
    private boolean surfaceReleased;
    private boolean textureReleased;
    private boolean finished;
    private boolean draining;

    private enum Step { SURFACE, TEXTURE, FINISHED }

    CarPlayConsumerRetirement(Runnable releaseSurface, Runnable releaseTexture, Runnable onFinished) {
        this.releaseSurface = releaseSurface;
        this.releaseTexture = releaseTexture;
        this.onFinished = onFinished;
    }

    void confirmStopped() {
        synchronized (this) { producerStopped = true; }
        releaseIfReady();
    }

    void detached() {
        synchronized (this) { detached = true; }
        releaseIfReady();
    }

    synchronized boolean isStopped() { return producerStopped; }
    synchronized boolean isFinished() { return finished; }

    private void releaseIfReady() {
        synchronized (this) {
            if (draining) { return; }
            draining = true;
        }
        try {
            while (true) {
                Step step;
                synchronized (this) {
                    if (producerStopped && !surfaceReleased) { step = Step.SURFACE; }
                    else if (detached && surfaceReleased && !textureReleased) { step = Step.TEXTURE; }
                    else if (textureReleased && !finished) { step = Step.FINISHED; }
                    else { draining = false; return; }
                }
                switch (step) {
                    case SURFACE: releaseSurface.run(); break;
                    case TEXTURE: releaseTexture.run(); break;
                    case FINISHED: onFinished.run(); break;
                }
                synchronized (this) {
                    switch (step) {
                        case SURFACE: surfaceReleased = true; break;
                        case TEXTURE: textureReleased = true; break;
                        case FINISHED: finished = true; break;
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) { draining = false; }
            throw failure;
        }
    }
}
