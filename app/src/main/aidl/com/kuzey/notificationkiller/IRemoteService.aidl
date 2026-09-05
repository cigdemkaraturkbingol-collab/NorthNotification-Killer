package com.kuzey.notificationkiller;

interface IRemoteService {
    String runCommand(String command);
    void destroy();
}
