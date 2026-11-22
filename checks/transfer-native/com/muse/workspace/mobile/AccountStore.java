package com.muse.workspace.mobile;
final class AccountStore {
    static final class Account{final String id;Account(String id){this.id=id;}String profileName(){return "muse-"+id;}}
    boolean usable(Account account,boolean profiles){return profiles||account.id.equals("fallback");}
}
