package com.muse.workspace.mobile;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native returned attributes and explicitly labelled legacy request headers; no URL decoding. */
final class AccountTransferCookieText {
    static List<AccountTransferBundle.Cookie> header(String raw,String url)throws AccountTransferCrypto.TransferException{
        if(raw==null||raw.length()>1024*1024||raw.indexOf('\r')>=0||raw.indexOf('\n')>=0)throw AccountTransferJson.invalid();
        String text=raw.trim();if(text.regionMatches(true,0,"Cookie:",0,7))text=text.substring(7).trim();
        List<AccountTransferBundle.Cookie> result=new ArrayList<>();Set<String> names=new HashSet<>();
        for(String pair:text.split(";",-1)){
            pair=pair.trim();if(pair.isEmpty())continue;int equal=pair.indexOf('=');if(equal<1)throw AccountTransferJson.invalid();
            String name=pair.substring(0,equal).trim(),value=pair.substring(equal+1).trim();
            if(!names.add(name))throw AccountTransferJson.invalid();
            result.add(new AccountTransferBundle.Cookie(url,name,value,null,null,null,null,null,null,null,null));
        }
        if(result.isEmpty()||result.size()>256)throw AccountTransferJson.invalid();return result;
    }

    static AccountTransferBundle.Cookie fromSetCookie(String text,String url)throws AccountTransferCrypto.TransferException{
        if(text==null||text.length()>20000)throw AccountTransferJson.invalid();
        String[] parts=text.split(";",-1);int equal=parts[0].indexOf('=');if(equal<1)throw AccountTransferJson.invalid();
        String name=parts[0].substring(0,equal).trim(),value=parts[0].substring(equal+1).trim();
        String domain=null,path=null,sameSite="unspecified";Long expires=null;boolean secure=false,httpOnly=false;Set<String> seen=new HashSet<>();
        for(int i=1;i<parts.length;i++){
            String part=parts[i].trim();if(part.isEmpty())continue;int separator=part.indexOf('=');
            String key=(separator<0?part:part.substring(0,separator)).trim().toLowerCase(Locale.ROOT),data=separator<0?"":part.substring(separator+1).trim();
            if(!seen.add(key))throw AccountTransferJson.invalid();
            switch(key){
                case "domain"->domain=data.toLowerCase(Locale.ROOT);case "path"->path=data;
                case "secure"->secure=true;case "httponly"->httpOnly=true;
                case "samesite"->sameSite=data.toLowerCase(Locale.ROOT);
                case "expires"->{try{expires=ZonedDateTime.parse(data,DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();}catch(DateTimeParseException error){throw AccountTransferJson.invalid();}}
                case "partitioned","max-age"->throw new AccountTransferCrypto.TransferException("unsupported-cookie");
                default->{ /* The API's normalized output may include non-authentication advisory attributes. */ }
            }
        }
        return new AccountTransferBundle.Cookie(url,name,value,domain,path,expires,secure,httpOnly,sameSite,expires==null,null);
    }
}
