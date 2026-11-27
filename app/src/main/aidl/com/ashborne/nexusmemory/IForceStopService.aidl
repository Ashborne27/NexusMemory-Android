package com.ashborne.nexusmemory;

interface IForceStopService {
    int forceStopPackage(String packageName);
    void destroy();
    String getRunningRamSummary();
}
