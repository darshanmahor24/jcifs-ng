package jcifs.pac.kerberos;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.Enumeration;

import javax.security.auth.kerberos.KerberosKey;
import javax.security.auth.login.LoginException;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERGeneralString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DLSequence;

import jcifs.pac.PACDecodingException;
import jcifs.util.ASN1Util;


public class KerberosTicket {

    private String serverPrincipalName;
    private String serverRealm;
    private KerberosEncData encData;


    public KerberosTicket ( byte[] token, byte apOptions, KerberosKey[] keys ) throws PACDecodingException {
        if ( token.length <= 0 )
            throw new PACDecodingException("Empty kerberos ticket");

        DLSequence sequence;
        try {
            try ( ASN1InputStream stream = new ASN1InputStream(new ByteArrayInputStream(token)) ) {
                sequence = ASN1Util.as(DLSequence.class, stream);
            }
        }
        catch ( IOException e ) {
            throw new PACDecodingException("Malformed kerberos ticket", e);
        }

        Enumeration<?> fields = sequence.getObjects();
        while ( fields.hasMoreElements() ) {
            ASN1TaggedObject tagged = ASN1Util.as(ASN1TaggedObject.class, fields);
            switch ( tagged.getTagNo() ) {
            case 0:// Kerberos version
                ASN1Integer tktvno = ASN1Util.as(ASN1Integer.class, tagged);
                if ( !tktvno.getValue().equals(new BigInteger(KerberosConstants.KERBEROS_VERSION)) ) {
                    throw new PACDecodingException("Invalid kerberos version " + tktvno);
                }
                break;
            case 1:// Realm
                DERGeneralString derRealm = ASN1Util.as(DERGeneralString.class, tagged);
                this.serverRealm = derRealm.getString();
                break;
            case 2:// Principal
                DLSequence principalSequence = ASN1Util.as(DLSequence.class, tagged);
                DLSequence nameSequence = ASN1Util.as(DLSequence.class, ASN1Util.as(DERTaggedObject.class, principalSequence, 1));

                StringBuilder nameBuilder = new StringBuilder();
                Enumeration<?> parts = nameSequence.getObjects();
                while ( parts.hasMoreElements() ) {
                    Object part = parts.nextElement();
                    DERGeneralString stringPart = ASN1Util.as(DERGeneralString.class, part);
                    nameBuilder.append(stringPart.getString());
                    if ( parts.hasMoreElements() )
                        nameBuilder.append('/');
                }
                this.serverPrincipalName = nameBuilder.toString();
                break;
            case 3:// Encrypted part
                DLSequence encSequence = ASN1Util.as(DLSequence.class, tagged);
                ASN1Integer encType = ASN1Util.as(ASN1Integer.class, ASN1Util.as(DERTaggedObject.class, encSequence, 0));
                DEROctetString encOctets = ASN1Util.as(DEROctetString.class, ASN1Util.as(DERTaggedObject.class, encSequence, 2));
                byte[] crypt = encOctets.getOctets();

                if ( keys == null ) {
                    try {
                        keys = new KerberosCredentials().getKeys();
                    }
                    catch ( LoginException e ) {
                        throw new PACDecodingException("Login failure", e);
                    }
                }

                KerberosKey serverKey = null;
                for ( KerberosKey key : keys ) {
                    if ( key.getKeyType() == encType.getValue().intValue() )
                        serverKey = key;
                }

                if ( serverKey == null ) {
                    throw new PACDecodingException("Kerberos key not found for eType " + encType.getValue());
                }

                try {
                    byte[] decrypted = KerberosEncData.decrypt(crypt, serverKey, serverKey.getKeyType());
                    this.encData = new KerberosEncData(decrypted, serverKey);
                }
                catch ( GeneralSecurityException e ) {
                    throw new PACDecodingException("Decryption failed " + serverKey.getKeyType(), e);
                }
                break;
            default:
                throw new PACDecodingException("Unrecognized field " + tagged.getTagNo());
            }
        }

    }


    public String getUserPrincipalName () {
        return this.encData.getUserPrincipalName();
    }


    public String getUserRealm () {
        return this.encData.getUserRealm();
    }


    public String getServerPrincipalName () {
        return this.serverPrincipalName;
    }


    public String getServerRealm () {
        return this.serverRealm;
    }


    public KerberosEncData getEncData () {
        return this.encData;
    }

}