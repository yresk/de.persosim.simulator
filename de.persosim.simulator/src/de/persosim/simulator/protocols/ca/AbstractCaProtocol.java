package de.persosim.simulator.protocols.ca;

import static de.persosim.simulator.protocols.Tr03110Utils.buildAuthenticationTokenInput;
import static de.persosim.simulator.utils.PersoSimLogger.DEBUG;
import static de.persosim.simulator.utils.PersoSimLogger.TRACE;
import static de.persosim.simulator.utils.PersoSimLogger.log;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;

import de.persosim.simulator.apdu.ResponseApdu;
import de.persosim.simulator.cardobjects.CardObject;
import de.persosim.simulator.cardobjects.CardObjectIdentifier;
import de.persosim.simulator.cardobjects.KeyIdentifier;
import de.persosim.simulator.cardobjects.KeyObject;
import de.persosim.simulator.cardobjects.MasterFile;
import de.persosim.simulator.cardobjects.MasterFileIdentifier;
import de.persosim.simulator.cardobjects.OidIdentifier;
import de.persosim.simulator.cardobjects.Scope;
import de.persosim.simulator.crypto.Crypto;
import de.persosim.simulator.crypto.CryptoSupport;
import de.persosim.simulator.crypto.DomainParameterSet;
import de.persosim.simulator.crypto.KeyDerivationFunction;
import de.persosim.simulator.crypto.StandardizedDomainParameters;
import de.persosim.simulator.exception.ProcessingException;
import de.persosim.simulator.platform.Iso7816;
import de.persosim.simulator.protocols.AbstractProtocolStateMachine;
import de.persosim.simulator.protocols.ProtocolUpdate;
import de.persosim.simulator.protocols.Tr03110Utils;
import de.persosim.simulator.protocols.Tr03110;
import de.persosim.simulator.protocols.ta.TerminalAuthenticationMechanism;
import de.persosim.simulator.secstatus.SecMechanism;
import de.persosim.simulator.secstatus.SecStatus.SecContext;
import de.persosim.simulator.secstatus.SecStatusMechanismUpdatePropagation;
import de.persosim.simulator.securemessaging.SmDataProviderTr03110;
import de.persosim.simulator.tlv.ConstructedTlvDataObject;
import de.persosim.simulator.tlv.PrimitiveTlvDataObject;
import de.persosim.simulator.tlv.TlvConstants;
import de.persosim.simulator.tlv.TlvDataObject;
import de.persosim.simulator.tlv.TlvDataObjectContainer;
import de.persosim.simulator.tlv.TlvPath;
import de.persosim.simulator.tlv.TlvTag;
import de.persosim.simulator.tlv.TlvValue;
import de.persosim.simulator.utils.HexString;
import de.persosim.simulator.utils.Utils;

/**
 * This class is part of the implementation of the Chip Authentication (CA)
 * protocol version 2 and implements basic methods to be used by
 * {@link DefaultCaProtocol}.
 * 
 * @author slutters
 * 
 */
//XXX SLS generalize code overlapping with {@link AbstractPaceProtocol} where possible.
public abstract class AbstractCaProtocol extends AbstractProtocolStateMachine implements Ca, TlvConstants {
	protected SecureRandom secureRandom;
	
	protected CaOid caOid;
	
	protected DomainParameterSet caDomainParameters;
	protected String keyAgreementAlgorithmName;
	
	protected CryptoSupport cryptoSupport;
	
	protected int keyReference;
	protected KeyPair staticKeyPairPicc;
	
	protected SecretKeySpec secretKeySpecMAC;
	protected SecretKeySpec secretKeySpecENC;
	
	
	
	public AbstractCaProtocol() {
		super("CA");
		
		secureRandom = new SecureRandom();
	}
	
	@Override
	public void initialize() {
		
	}
	
	protected CaOid extractCaOidFromCommandData(TlvDataObjectContainer commandData) {
		/* CA OID */
		/* Check for the CA OID for itself */
		/* tlvObject will never be null if APDU passed check against APDU specification */
		TlvDataObject tlvObject = commandData.getTlvDataObject(TAG_80);
		
		CaOid caOid;
		
		try {
			caOid = new CaOid(tlvObject.getValueField());
		} catch (RuntimeException e) {
			throw new ProcessingException(Iso7816.SW_6A80_WRONG_DATA, e.getMessage());
		}
		
		log(this, "new OID is " + caOid, DEBUG);
		return caOid;
	}
	
	protected KeyIdentifier extractKeyIdentifierFromCommandData(TlvDataObjectContainer commandData) {
		/* key reference */
		/* tlvObject may be null if key material is to be implicitly selected */
		TlvDataObject tlvObject = commandData.getTlvDataObject(TAG_84);
		
		KeyIdentifier keyIdentifier;
		if(tlvObject == null) {
			keyIdentifier = new KeyIdentifier();
		} else{
			keyIdentifier = new KeyIdentifier(tlvObject.getValueField());
		}
		
		return keyIdentifier;
	}
	
	protected KeyObject getkeyObjectForKeyIdentifier(KeyIdentifier keyIdentifier, CardObjectIdentifier... cardObjectIdentifier) {
		CardObject cardObject;
		try {
			cardObject = Tr03110Utils.getSpecificChild(cardState.getObject(new MasterFileIdentifier(), Scope.FROM_MF), keyIdentifier);
		} catch (IllegalArgumentException e) {
			throw new ProcessingException(Iso7816.SW_6A88_REFERENCE_DATA_NOT_FOUND, e.getMessage());
		}
		
		KeyObject keyObject;
		if((cardObject instanceof KeyObject)) {
			keyObject = (KeyObject) cardObject;
		} else{
			throw new ProcessingException(Iso7816.SW_6984_REFERENCE_DATA_NOT_USABLE, "invalid key reference");
		}
		
		if(cardObjectIdentifier != null) {
			for(CardObjectIdentifier coi: cardObjectIdentifier) {
				if(!keyObject.matchesIdentifier(coi)) {
					throw new ProcessingException(Iso7816.SW_6985_CONDITIONS_OF_USE_NOT_SATISFIED, "invalid key reference");
				}
			}
		}
		
		return keyObject;
	}
	
	/**
	 * This method performs the processing of the CA Set AT command.
	 */
	public void processCommandSetAT() {
		try {
			//get commandDataContainer
			TlvDataObjectContainer commandData = processingData.getCommandApdu().getCommandDataObjectContainer();
			
			caOid = extractCaOidFromCommandData(commandData);
			
			KeyIdentifier keyIdentifier = extractKeyIdentifierFromCommandData(commandData);
			OidIdentifier caOidIdentifier = new OidIdentifier(caOid);
			KeyObject keyObject = getkeyObjectForKeyIdentifier(keyIdentifier, caOidIdentifier);
			
			staticKeyPairPicc = keyObject.getKeyPair();
			keyReference = keyObject.getPrimaryIdentifier().getInteger();
			
			/* CA domain parameters */
			caDomainParameters = Tr03110Utils.getDomainParameterSetFromKey(staticKeyPairPicc.getPublic());
			
			this.cryptoSupport = caOid.getCryptoSupport();
			
			ResponseApdu resp = new ResponseApdu(Iso7816.SW_9000_NO_ERROR);
			processingData.updateResponseAPDU(this, "Command Set AT successfully processed", resp);
		} catch (ProcessingException e) {
			ResponseApdu resp = new ResponseApdu(e.getStatusWord());
			processingData.updateResponseAPDU(this, e.getMessage(), resp);
		}
	}
	
	/**
	 * This method reconstructs the PCD's public key sent with General Authenticate
	 * @param publicKeyMaterialPcd encoded kley material of the PCD's public key
	 * @return the PCD's public key
	 */
	protected PublicKey reconstructEphemeralPublicKeyPcd(byte[] publicKeyMaterialPcd) {
		PublicKey ephemeralPublicKeyPcd;
		
		try {
			ephemeralPublicKeyPcd = caDomainParameters.reconstructPublicKey(publicKeyMaterialPcd);
			log(this, "PCD's  ephemeral public " + keyAgreementAlgorithmName + " key is " + new TlvDataObjectContainer(ephemeralPublicKeyPcd.getEncoded()), TRACE);
		} catch (IllegalArgumentException e) {
			throw new ProcessingException(Iso7816.SW_6A80_WRONG_DATA, e.getMessage());
		} catch (Exception e) {
			throw new ProcessingException(Iso7816.SW_6FFF_IMPLEMENTATION_ERROR, e.getMessage());
		}
		
		return ephemeralPublicKeyPcd;
	}
	
	/**
	 * This method checks that the PCD's public key matches the compressed key received during previous TA
	 * @param ephemeralPublicKeyPcd the PCD's public key
	 */
	protected void assertEphemeralPublicKeyPcdMatchesCompressedKeyReceivedDuringTa(PublicKey ephemeralPublicKeyPcd) {
		//compare expected PCD's (compressed) public key with the key previously received during TA
		byte[] ephemeralPublicKeyPcdCompressedExpected;
		try {
			ephemeralPublicKeyPcdCompressedExpected = caDomainParameters.comp(ephemeralPublicKeyPcd);
		} catch (NoSuchAlgorithmException e) {
			throw new ProcessingException(Iso7816.SW_6FFF_IMPLEMENTATION_ERROR, e.getMessage());
		}
		
		byte[] ephemeralPublicKeyPcdCompressedReceived = getEphemeralPublicKeyPcdFromTa();
		
		if(ephemeralPublicKeyPcdCompressedReceived == null) {
			throw new ProcessingException(Iso7816.SW_6982_SECURITY_STATUS_NOT_SATISFIED, "PICC's compressed ephemeral public key from TA is missing. Maybe TA was not performed.");
		}
		
		log(this, "expected compressed PCD's ephemeral public " + keyAgreementAlgorithmName + " key of " + ephemeralPublicKeyPcdCompressedExpected.length + " bytes length is: " + HexString.encode(ephemeralPublicKeyPcdCompressedExpected), DEBUG);
		log(this, "received compressed PCD's ephemeral public " + keyAgreementAlgorithmName + " key of " + ephemeralPublicKeyPcdCompressedReceived.length + " bytes length is: " + HexString.encode(ephemeralPublicKeyPcdCompressedReceived), DEBUG);
		
		if(Arrays.equals(ephemeralPublicKeyPcdCompressedExpected, ephemeralPublicKeyPcdCompressedReceived)) {
			log(this, "compressed representation of PCD's ephemeral public " + caDomainParameters.getKeyAgreementAlgorithm() + " key matches the one received during previous TA", DEBUG);
		} else{
			throw new ProcessingException(Iso7816.SW_6984_REFERENCE_DATA_NOT_USABLE, "compressed representation of PCD's public " + keyAgreementAlgorithmName + " key does NOT match the one received during previous TA");
		}
	}
	
	/**
	 * This method performs the ca key agreement
	 * @param staticPrivateKeyPicc the private key to use
	 * @param ephemeralPublicKeyPcd the public key to use
	 * @return the shared secret
	 */
	protected byte[] performKeyAgreement(PrivateKey staticPrivateKeyPicc, PublicKey ephemeralPublicKeyPcd) {
		//perform key agreement
		KeyAgreement keyAgreement;
		byte[] sharedSecret = null;
		
		try {
			keyAgreement = KeyAgreement.getInstance(caOid.getKeyAgreementName(), Crypto.getCryptoProvider());
			keyAgreement.init(staticPrivateKeyPicc);
			keyAgreement.doPhase(ephemeralPublicKeyPcd, true);
			sharedSecret = keyAgreement.generateSecret();
		} catch (InvalidKeyException e) {
			throw new ProcessingException(Iso7816.SW_6A80_WRONG_DATA, "invalid key");
		} catch(NoSuchAlgorithmException | IllegalStateException e) {
			throw new ProcessingException(Iso7816.SW_6FFF_IMPLEMENTATION_ERROR, e.getMessage());
		}
		
		log(this, "shared secret K of " + sharedSecret.length + " bytes length is: " + HexString.encode(sharedSecret), DEBUG);
		
		return sharedSecret;
	}
	
	/**
	 * This method computes the CA session keys
	 * @param sharedSecret the shared secret used to compute the session keys
	 * @param rPiccNonce the PICC's nonce r used to generate the session keys
	 */
	protected void computeSessionKeys(byte[] sharedSecret, byte[] rPiccNonce) {
		//compute session keys
		KeyDerivationFunction kdf = new KeyDerivationFunction(caOid.getSymmetricCipherKeyLengthInBytes());
		
		byte[] keyMaterialMac = kdf.deriveMAC(sharedSecret, rPiccNonce);
		byte[] keyMaterialEnc = kdf.deriveENC(sharedSecret, rPiccNonce);
		
		log(this, "PICC's session key for MAC of " + keyMaterialMac.length + " bytes length is: " + HexString.encode(keyMaterialMac), DEBUG);
		log(this, "PICC's session key for ENC of " + keyMaterialMac.length + " bytes length is: " + HexString.encode(keyMaterialEnc), DEBUG);
		
		secretKeySpecMAC = cryptoSupport.generateSecretKeySpecMac(keyMaterialMac);
		secretKeySpecENC = cryptoSupport.generateSecretKeySpecCipher(keyMaterialEnc);
	}
	
	/**
	 * This method generates the PICC's nonce r
	 * @return the PICC's nonce r
	 */
	protected byte[] generateRPiccNonce() {
		//get nonce r_PICC
		int nonceSizeInBytes = 8;
		byte[] rPiccNonce = new byte[nonceSizeInBytes];
		this.secureRandom.nextBytes(rPiccNonce);
		log(this, "nonce r_PICC of " + nonceSizeInBytes + " bytes length is: " + HexString.encode(rPiccNonce), DEBUG);
		return rPiccNonce;
	}
	
	/**
	 * This method computes the PICC's authentication token
	 * @param ephemeralPublicKeyPcd the PCD's ephemeral public key
	 * @return the PICC's authentication token
	 */
	protected byte[] computeAuthenticationTokenTpicc(PublicKey ephemeralPublicKeyPcd) {
		//compute authentication token T_PICC
		TlvDataObjectContainer authenticationTokenInput = buildAuthenticationTokenInput(ephemeralPublicKeyPcd, caDomainParameters, caOid);
		log(this, "authentication token raw data " + authenticationTokenInput, DEBUG);
		byte[] authenticationTokenTpicc = Arrays.copyOf(this.cryptoSupport.macAuthenticationToken(authenticationTokenInput.toByteArray(), this.secretKeySpecMAC), 8);
		log(this, "PICC's authentication token T_PICC of " + authenticationTokenTpicc.length + " bytes length is: " + HexString.encode(authenticationTokenTpicc), DEBUG);
		
		return authenticationTokenTpicc;
	}
	
	/**
	 * This method prepares the response data to be sent within the response APDU
	 * @param rPiccNonce the PICC's nonce r
	 * @param authenticationTokenTpicc the PICC's authentication token
	 * @return the response data to be sent within the response APDU
	 */
	protected TlvValue prepareResponseData(byte[] rPiccNonce, byte[] authenticationTokenTpicc) {
		//create and prepare response APDU
		PrimitiveTlvDataObject primitive81 = new PrimitiveTlvDataObject(TAG_81, rPiccNonce);
		log(this, "primitive tag 81 is: " + primitive81, TRACE);
		PrimitiveTlvDataObject primitive82 = new PrimitiveTlvDataObject(TAG_82, authenticationTokenTpicc);
		log(this, "primitive tag 82 is: " + primitive82, TRACE);
		ConstructedTlvDataObject constructed7C = new ConstructedTlvDataObject(TAG_7C);
		constructed7C.addTlvDataObject(primitive81);
		constructed7C.addTlvDataObject(primitive82);
		
		log(this, "response data to be sent is: " + constructed7C, DEBUG);
		
		//create and propagate response APDU
		TlvValue responseData = new TlvDataObjectContainer(constructed7C);
		
		return responseData;
	}
	
	/**
	 * This method propagates the session keys to be used for secure messaging 
	 */
	protected void propagateSessionKeys() {
		//create and propagate new secure messaging data provider
		SmDataProviderTr03110 smDataProvider;
		try {
			smDataProvider = new SmDataProviderTr03110(this.secretKeySpecENC, this.secretKeySpecMAC);
			processingData.addUpdatePropagation(this, "init SM after successful CA", smDataProvider);
		} catch (GeneralSecurityException e) {
			throw new ProcessingException(Iso7816.SW_6FFF_IMPLEMENTATION_ERROR, "Unable to initialize new secure messaging");
		}
	}
	
	/**
	 * This method retrieves the PCD's public key material from the received General Authenticate APDU
	 * @return the PCD's public key material
	 */
	protected byte[] getPcdPublicKeyMaterialFromApdu() {
		//retrieve command data
		TlvDataObjectContainer commandData = processingData.getCommandApdu().getCommandDataObjectContainer();
		
		//retrieve PCD's public key
		TlvDataObject tlvObject = commandData.getTlvDataObject(new TlvPath(new TlvTag((byte) 0x7C), new TlvTag((byte) 0x80)));
		byte[] pcdPublicKeyMaterial = tlvObject.getValueField();
		
		keyAgreementAlgorithmName = caDomainParameters.getKeyAgreementAlgorithm();
		log(this, "PCD's ephemeral public " + keyAgreementAlgorithmName + " key material of " + pcdPublicKeyMaterial.length + " bytes length is: " + HexString.encode(pcdPublicKeyMaterial), TRACE);
		
		return pcdPublicKeyMaterial;
	}
	
	/**
	 * This method performs the processing of the CA General Authenticate
	 * command.
	 */
	public void processCommandGeneralAuthenticate() {
		try {
			byte[] pcdPublicKeyMaterial = getPcdPublicKeyMaterialFromApdu();
			PublicKey ephemeralPublicKeyPcd = reconstructEphemeralPublicKeyPcd(pcdPublicKeyMaterial);
			assertEphemeralPublicKeyPcdMatchesCompressedKeyReceivedDuringTa(ephemeralPublicKeyPcd);
			byte[] sharedSecret = performKeyAgreement(staticKeyPairPicc.getPrivate(), ephemeralPublicKeyPcd);
			byte[] rPiccNonce = generateRPiccNonce();
			computeSessionKeys(sharedSecret, rPiccNonce);
			byte[] authenticationTokenTpicc = computeAuthenticationTokenTpicc(ephemeralPublicKeyPcd);
			propagateSessionKeys();
			
			ChipAuthenticationMechanism mechanism = new ChipAuthenticationMechanism(caOid, keyReference, ephemeralPublicKeyPcd);
			processingData.addUpdatePropagation(this, "Updated security status with chip authentication information", new SecStatusMechanismUpdatePropagation(SecContext.APPLICATION, mechanism));
			
			TlvValue responseData = prepareResponseData(rPiccNonce, authenticationTokenTpicc);
			
			ResponseApdu resp = new ResponseApdu(responseData, Iso7816.SW_9000_NO_ERROR);
			processingData.updateResponseAPDU(this, "Command General Authenticate successfully processed", resp);
			
			/* 
			 * Request removal of this instance from the stack.
			 * Protocol either successfully completed or failed.
			 * In either case protocol is completed.
			 */
			processingData.addUpdatePropagation(this, "Command General Authenticate successfully processed - Protocol CA completed", new ProtocolUpdate(true));
		} catch (ProcessingException e) {
			ResponseApdu resp = new ResponseApdu(e.getStatusWord());
			processingData.updateResponseAPDU(this, e.getMessage(), resp);
		}
	}
	
	/**
	 * This method retrieves the PCD's ephemeral public key material received during TA
	 * @return the PCD's ephemeral public key material
	 */
	private byte[] getEphemeralPublicKeyPcdFromTa() {
		byte[] ephemeralPublicKeyPcdCompressedReceived = null;
		
		Collection<Class<? extends SecMechanism>> wantedMechanisms = new HashSet<Class<? extends SecMechanism>>();
		wantedMechanisms.add(TerminalAuthenticationMechanism.class);
		Collection<SecMechanism> currentMechanisms = cardState.getCurrentMechanisms(SecContext.APPLICATION, wantedMechanisms);
		
		for(SecMechanism secMechanism : currentMechanisms) {
			if(secMechanism instanceof TerminalAuthenticationMechanism) {
				ephemeralPublicKeyPcdCompressedReceived = ((TerminalAuthenticationMechanism) secMechanism).getCompressedTerminalEphemeralPublicKey();
				break; // there is at most one TerminalAuthenticationMechanism
			}
		}
		
		return ephemeralPublicKeyPcdCompressedReceived;
	}

	@Override
	public Collection<TlvDataObject> getSecInfos(SecInfoPublicity publicity, MasterFile mf) {
		
		OidIdentifier caOidIdentifier = new OidIdentifier(OID_id_CA);
		
		Collection<CardObject> caKeyCardObjects = mf.findChildren(
				new KeyIdentifier(), caOidIdentifier);
		
		ArrayList<TlvDataObject> secInfos = new ArrayList<>();
		ArrayList<TlvDataObject> privilegedSecInfos = new ArrayList<>();
		ArrayList<TlvDataObject> unprivilegedPublicKeyInfos = new ArrayList<>();
		ArrayList<TlvDataObject> privilegedPublicKeyInfos = new ArrayList<>();
		
		
		for (CardObject curObject : caKeyCardObjects) {
			if (! (curObject instanceof KeyObject)) {
				continue;
			}
			KeyObject curKey = (KeyObject) curObject;
			Collection<CardObjectIdentifier> identifiers = curKey.getAllIdentifiers();
			
			//extract keyId
			int keyId = -1;
			for (CardObjectIdentifier curIdentifier : identifiers) {
				if (curIdentifier instanceof KeyIdentifier) {
					keyId = ((KeyIdentifier) curIdentifier).getKeyReference();
					break;
				}
			}
			if (keyId == -1) continue; // skip keys that dont't provide a keyId
			
			//cached values
			byte[] genericCaOidBytes = null;
			
			//construct and add CaInfos
			for (CardObjectIdentifier curIdentifier : identifiers) {
				if (caOidIdentifier.matches(curIdentifier)) {
					byte[] oidBytes = ((OidIdentifier) curIdentifier).getOid().toByteArray();
					genericCaOidBytes = Arrays.copyOfRange(oidBytes, 0, 9);
					
					ConstructedTlvDataObject caInfo = new ConstructedTlvDataObject(TAG_SEQUENCE);
					caInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_OID, oidBytes));
					caInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_INTEGER, new byte[]{2}));
					caInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_INTEGER, new byte[]{(byte) keyId}));
					
					if (curKey.isPrivilegedOnly()) {
						privilegedSecInfos.add(caInfo);
					} else {
						secInfos.add(caInfo);
					}
				}
			}
			
			//extract required data from curKey
			ConstructedTlvDataObject encKey = new ConstructedTlvDataObject(curKey.getKeyPair().getPublic().getEncoded());
			ConstructedTlvDataObject algIdentifier = (ConstructedTlvDataObject) encKey.getTlvDataObject(TAG_SEQUENCE);
			TlvDataObject subjPubKey = encKey.getTlvDataObject(TAG_BIT_STRING);
			
			//using standardized domain parameters if possible
			algIdentifier = StandardizedDomainParameters.simplifyAlgorithmIdentifier(algIdentifier);
			
			//add CaDomainParameterInfo
			ConstructedTlvDataObject caDomainInfo = new ConstructedTlvDataObject(TAG_SEQUENCE);
			caDomainInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_OID, genericCaOidBytes));
			caDomainInfo.addTlvDataObject(algIdentifier);
			caDomainInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_INTEGER, new byte[]{(byte) keyId}));
			if (curKey.isPrivilegedOnly()) {
				privilegedSecInfos.add(caDomainInfo);
			} else {
				secInfos.add(caDomainInfo);
			}
			
			//build SubjectPublicKeyInfo
			ConstructedTlvDataObject subjPubKeyInfo = new ConstructedTlvDataObject(TAG_SEQUENCE);
			subjPubKeyInfo.addTlvDataObject(algIdentifier);
			subjPubKeyInfo.addTlvDataObject(subjPubKey);
			
			if ((publicity == SecInfoPublicity.AUTHENTICATED) || (publicity == SecInfoPublicity.PRIVILEGED)) {
				//add CaPublicKeyInfo
				ConstructedTlvDataObject caPublicKeyInfo = new ConstructedTlvDataObject(TAG_SEQUENCE);
				caPublicKeyInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_OID, Utils.concatByteArrays(Tr03110.id_PK, new byte[] {genericCaOidBytes[8]})));
				caPublicKeyInfo.addTlvDataObject(subjPubKeyInfo);
				caPublicKeyInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_INTEGER, new byte[]{(byte) keyId}));
				
				
				if (curKey.isPrivilegedOnly()) {
					privilegedPublicKeyInfos.add(caPublicKeyInfo);
				} else {
					unprivilegedPublicKeyInfos.add(caPublicKeyInfo);
				}
			}
			
		}
		
		// add publicKeys if publicity allows
		if ((publicity == SecInfoPublicity.AUTHENTICATED) || (publicity == SecInfoPublicity.PRIVILEGED)) {
			secInfos.addAll(unprivilegedPublicKeyInfos);
		}
		
		//add PrivilegedTerminalInfo if privileged keys are available
		if (privilegedSecInfos.size() + privilegedPublicKeyInfos.size() > 0) {
			ConstructedTlvDataObject privilegedTerminalInfo = new ConstructedTlvDataObject(TAG_SEQUENCE);
			privilegedTerminalInfo.addTlvDataObject(new PrimitiveTlvDataObject(TAG_OID, Tr03110.id_PT));
			ConstructedTlvDataObject privilegedTerminaInfoSet = new ConstructedTlvDataObject(TAG_SET);
			privilegedTerminalInfo.addTlvDataObject(privilegedTerminaInfoSet);
			
			// add all privileged infos
			privilegedTerminaInfoSet.addAll(privilegedSecInfos);
		
			// add privileged public keys if publicity allows
			if ((publicity == SecInfoPublicity.PRIVILEGED)) {
				privilegedTerminaInfoSet.addAll(privilegedPublicKeyInfos);
			}
			
			secInfos.add(privilegedTerminalInfo);
		}
		
		return secInfos;
	}
	
}
