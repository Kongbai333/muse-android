package com.muse.workspace.mobile;

/** Persist named choices rather than binding preferences to the current system color. */
enum ThemeMode {
    LIGHT("light","白天"), DARK("dark","黑夜"), SYSTEM("system","跟随系统");
    final String stored,label;
    ThemeMode(String stored,String label){this.stored=stored;this.label=label;}
    static ThemeMode read(String stored,Boolean legacyDark){
        for(ThemeMode mode:values())if(mode.stored.equals(stored))return mode;
        return legacyDark==null?SYSTEM:legacyDark?DARK:LIGHT;
    }
}
