package com.muse.workspace.mobile;
import java.nio.file.Files;
import java.nio.file.Path;
public final class AccountTransferJsonCrossCheck {
    public static void main(String[] args)throws Exception{
        byte[] input=Files.readAllBytes(Path.of(args[0]));
        Files.write(Path.of(args[1]),AccountTransferJson.bytes(AccountTransferJson.parse(input)));
        for(byte[] bad:new byte[][]{{(byte)0xc0,(byte)0xaf},{(byte)0xef,(byte)0xbb,(byte)0xbf,'{','}'}}){
            try{AccountTransferJson.parse(bad);throw new AssertionError("Invalid UTF-8/BOM accepted");}catch(AccountTransferCrypto.TransferException expected){}
        }
    }
}
