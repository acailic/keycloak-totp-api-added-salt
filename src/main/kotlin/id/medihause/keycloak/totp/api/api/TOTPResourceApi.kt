package id.medihause.keycloak.totp.api.api

import id.medihause.keycloak.totp.api.dto.CommonApiResponse
import id.medihause.keycloak.totp.api.dto.GenerateTOTPResponse
import id.medihause.keycloak.totp.api.dto.RegisterTOTPCredentialRequest
import id.medihause.keycloak.totp.api.dto.VerifyTOTPRequest
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.keycloak.credential.CredentialProvider
import org.keycloak.models.KeycloakSession
import org.keycloak.models.UserCredentialModel
import org.keycloak.models.UserModel
import org.keycloak.models.credential.OTPCredentialModel
import org.keycloak.models.utils.Base32
import org.keycloak.models.utils.HmacOTP
import org.keycloak.services.managers.AppAuthManager
import org.keycloak.utils.CredentialHelper
import org.keycloak.utils.TotpUtils
import java.security.SecureRandom
import java.util.Base64

class TOTPResourceApi(
    private val session: KeycloakSession,
) {
    private val totpSecretLength = 20

    /**
     * Helper function to extract salt from a salted secret
     * Returns Pair<originalSecret, saltBase64> or null if no salt found
     */
    private fun extractSaltFromSecret(saltedSecret: String): Pair<String, String>? {
        println("DEBUG: Attempting to extract salt from secret (length: ${saltedSecret.length})")
        val saltPrefix = "|salt:"
        val saltIndex = saltedSecret.indexOf(saltPrefix)
        return if (saltIndex != -1) {
            val originalSecret = saltedSecret.substring(0, saltIndex)
            val saltBase64 = saltedSecret.substring(saltIndex + saltPrefix.length)
            println("DEBUG: Salt extraction successful - original secret length: ${originalSecret.length}, salt length: ${saltBase64.length}")
            Pair(originalSecret, saltBase64)
        } else {
            println("DEBUG: No salt prefix found in secret - treating as non-salted credential")
            null
        }
    }

    private fun authenticateSessionAndGetUser(
        userId: String
    ): UserModel {
        val auth = AppAuthManager.BearerTokenAuthenticator(session).authenticate()

        if (auth == null) {
            throw NotAuthorizedException("Token not valid", {})
        } else if (auth.user.serviceAccountClientLink == null) {
            throw NotAuthorizedException("User is not a service account", {})
        } else if (auth.token.realmAccess == null || !auth.token.realmAccess.isUserInRole("manage-totp")) {
            throw NotAuthorizedException("User is not an admin", {})
        }

        val user = session.users().getUserById(session.context.realm, userId)
            ?: throw NotFoundException("User not found")

        if (user.serviceAccountClientLink != null) {
            throw BadRequestException("Cannot manage service account")
        }

        return user
    }

    @GET
    @Path("/{userId}/generate")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    fun generateTOTP(@PathParam("userId") userId: String): Response {
        val user = authenticateSessionAndGetUser(userId)
        val realm = session.context.realm

        val secret = HmacOTP.generateSecret(totpSecretLength)
        val qrCode = TotpUtils.qrCode(secret, realm, user)
        val encodedSecret = Base32.encode(secret.toByteArray())

        return Response.ok().entity(
            GenerateTOTPResponse(
                encodedSecret = encodedSecret,
                qrCode = qrCode
            )
        ).build()
    }

    @POST
    @Path("/{userId}/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun verifyTOTP(request: VerifyTOTPRequest, @PathParam("userId") userId: String): Response {
        println("DEBUG: TOTP verification started for user $userId, device: ${request.deviceName}")
        val user = authenticateSessionAndGetUser(userId)

        if (!VerifyTOTPRequest.validate(request)) {
            println("DEBUG: Invalid TOTP request - deviceName or code empty")
            return Response.status(Response.Status.BAD_REQUEST).entity(CommonApiResponse("Invalid request")).build()
        }

        val credentialModel = user.credentialManager().getStoredCredentialByNameAndType(
            request.deviceName,
            OTPCredentialModel.TYPE
        )

        if (credentialModel == null) {
            println("DEBUG: TOTP credential not found for device: ${request.deviceName}")
            return Response.status(Response.Status.UNAUTHORIZED).entity(CommonApiResponse("TOTP credential not found"))
                .build()
        }

        println("DEBUG: TOTP credential found for device: ${request.deviceName}")

        val totpCredentialProvider = session.getProvider(CredentialProvider::class.java, "keycloak-otp")
        val totpCredentialModel = OTPCredentialModel.createFromCredentialModel(credentialModel)

        // Extract the original secret from the salted secret if salt is present
        val storedSecret = totpCredentialModel.secretData
        println("DEBUG: Stored secret format: ${if (storedSecret.contains("|salt:")) "SALTED" else "NON-SALTED"}")
        
        val extractedData = extractSaltFromSecret(storedSecret)
        
        val isCredentialValid = if (extractedData != null) {
            println("DEBUG: Salt detected - using salt extraction path")
            // Extract the original secret and use it for verification
            val (originalSecret, saltBase64) = extractedData
            println("DEBUG: Successfully extracted salt (${saltBase64.length} chars) and original secret")
            
            // Temporarily modify the credential model to use the original secret for verification
            // We create a copy and change only the secret data
            val originalSecretData = totpCredentialModel.secretData
            totpCredentialModel.secretData = originalSecret
            println("DEBUG: Temporarily replaced salted secret with original secret for verification")
            
            val credentialId = totpCredentialModel.id
            val isValid = user.credentialManager()
                .isValid(UserCredentialModel(credentialId, totpCredentialProvider.type, request.code))
            
            // Restore the original salted secret data
            totpCredentialModel.secretData = originalSecretData
            println("DEBUG: Restored salted secret after verification")
            println("DEBUG: Salt-based verification result: ${if (isValid) "SUCCESS" else "FAILED"}")
            
            isValid
        } else {
            println("DEBUG: No salt detected - using standard Keycloak verification path")
            // No salt found, use standard Keycloak verification (backward compatibility)
            val credentialId = totpCredentialModel.id
            val isValid = user.credentialManager()
                .isValid(UserCredentialModel(credentialId, totpCredentialProvider.type, request.code))
            println("DEBUG: Standard verification result: ${if (isValid) "SUCCESS" else "FAILED"}")
            
            isValid
        }

        return if (isCredentialValid) {
            println("DEBUG: Returning SUCCESS response - TOTP code is valid")
            Response.ok().entity(CommonApiResponse("TOTP code is valid")).build()
        } else {
            println("DEBUG: Returning FAILURE response - Invalid TOTP code")
            Response.status(Response.Status.UNAUTHORIZED).entity(CommonApiResponse("Invalid TOTP code")).build()
        }
    }

    @POST
    @Path("/{userId}/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun registerTOTP(request: RegisterTOTPCredentialRequest, @PathParam("userId") userId: String): Response {
        val user = authenticateSessionAndGetUser(userId)

        if (!RegisterTOTPCredentialRequest.validate(request)) {
            return Response.status(Response.Status.BAD_REQUEST).entity(CommonApiResponse("Invalid request")).build()
        }

        val encodedTOTP = request.encodedSecret
        val secretBytes = Base32.decode(encodedTOTP)
        val secret = String(secretBytes)

        if (secretBytes.size != totpSecretLength) {
            return Response.status(Response.Status.BAD_REQUEST).entity(CommonApiResponse("Invalid secret")).build()
        }

        val realm = session.context.realm
        val credentialModel = user.credentialManager().getStoredCredentialByNameAndType(
            request.deviceName,
            OTPCredentialModel.TYPE
        )

        if (credentialModel != null && !request.overwrite) {
            return Response.status(Response.Status.CONFLICT).entity(CommonApiResponse("TOTP credential already exists"))
                .build()
        }

        // Generate salt for additional security (Option 1: Store salt in secret data)
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        
        // Create a salted secret by combining the original secret with salt
        // This approach stores salt information within the secret data itself
        val saltedSecret = secret + "|salt:" + saltBase64
        
        // Create a new credential model with the salted secret
        val saltedTotpCredentialModel = OTPCredentialModel.createFromPolicy(realm, saltedSecret, request.deviceName)
        
        if (!CredentialHelper.createOTPCredential(session, realm, user, request.initialCode, saltedTotpCredentialModel)) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(CommonApiResponse("Failed to create TOTP credential")).build()
        }

        return Response.status(Response.Status.CREATED).entity(CommonApiResponse("TOTP credential registered")).build()
    }
}
