package com.example.demo.Service.Auth.Imp;

import com.example.demo.Common.AttributeConstant;
import com.example.demo.Common.ErrorCode;
import com.example.demo.Common.SuccessMessage;
import com.example.demo.Dto.Request.LoginRequest;
import com.example.demo.Dto.Request.RegisterRequest;
import com.example.demo.Dto.Request.SetPasswordRequest;
import com.example.demo.Dto.Response.RegisterResponse;
import com.example.demo.Dto.TokenDto;
import com.example.demo.Entity.Otp;
import com.example.demo.Entity.Role;
import com.example.demo.Entity.Token;
import com.example.demo.Entity.User;
import com.example.demo.Exception.BaseException;
import com.example.demo.Repository.OtpRepository;
import com.example.demo.Repository.RoleRepository;
import com.example.demo.Repository.TokenRepository;
import com.example.demo.Repository.UserRepository;
import com.example.demo.Service.Auth.AuthService;
import com.example.demo.Utill.EmailUtil;
import com.example.demo.Utill.OtpUtil;
import com.example.demo.Utill.SecurityUtil;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuthServiceImp implements AuthService {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenRepository tokenRepository;
    @Autowired
    private SecurityUtil securityUtil;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private OtpRepository otpRepository;
    @Autowired
    private OtpUtil otpUtil;
    @Autowired
    private EmailUtil emailUtil;
    @Override
    public TokenDto authenticate(LoginRequest request) {
        User user = findByEmail(request.getEmail());
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BaseException(ErrorCode.WRONG_PASSWORD);
        }
        String accessToken = securityUtil.createAccessToken(user);
        String refreshToken = securityUtil.createRefreshToken(user);
        Token token = Token.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(user)
                .build();
        tokenRepository.save(token);
        return TokenDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
    @Override
    public TokenDto refreshToken(HttpServletRequest request) {

        String authHeader = request.getHeader(AttributeConstant.HEADER_AUTHORIZATION);
        String refreshToken;
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BaseException(ErrorCode.FAILED);
        }
        refreshToken = authHeader.substring(7);
        Token token = tokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_REFRESH_TOKEN));

        User user = token.getUser();
        String newAccessToken = securityUtil.createAccessToken(user);
        String newRefreshToken = securityUtil.createRefreshToken(user);
        token.setAccessToken(newAccessToken);
        tokenRepository.save(token);
        return TokenDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }
    @Override
    public RegisterResponse saveUser(RegisterRequest registerRequest) {
        if (isEmailExist(registerRequest.getEmail())) {
            throw new BaseException(ErrorCode.USER_EXIST);
        }
        if (!registerRequest.getPassword().equals(registerRequest.getConfirmPassword())) {
            throw new BaseException(ErrorCode.WRONG_PASSWORD);
        }
        User user = new User();
        user.setFirstName(registerRequest.getFirstName());
        user.setLastName(registerRequest.getLastName());
        user.setEmail(registerRequest.getEmail());
        user.setPassword(registerRequest.getPassword());
        user.setCreatedAt(LocalDateTime.now());
        user.setLastLogin(LocalDateTime.now());
        user.setLastModified(LocalDateTime.now());
        String hashPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(hashPassword);
        Role userRole = roleRepository.findByName("user");
        if (registerRequest.getEmail().contains("admin")) {
            userRole = roleRepository.findByName("admin");
        }
        user.setRole(userRole);
        User savedUser = this.userRepository.save(user);  //save User
        return new RegisterResponse(  //Response Register
                savedUser.getFirstName(),
                savedUser.getLastName(),
                savedUser.getEmail());
    }
    @Override
    public Void logout(String refreshToken) {
        Token token = tokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_REFRESH_TOKEN));
        token.setRevoked(true);
        return null;
    }
    @Override
    public String generateOtp(String email) {
        User user = findByEmail(email);
        String otp = otpUtil.generateOtp();
        Otp generateOtp = Otp.builder()
                .user(user)
                .otp(otp)
                .build();
        otpRepository.save(generateOtp);
        try {
            emailUtil.sendOtpEmail(email, otp); // Send OTP to Email
        } catch (MessagingException e) {
            throw new BaseException(ErrorCode.FAILED);
        }
        return SuccessMessage.SUCCESS.getMessage();
    }
    @Override
    public TokenDto verifyAccount(String email, String otp) {
        User user = findByEmail(email);
        Otp geotp = otpRepository.findByUserAndOtp(user, otp)
                .orElseThrow(() -> new BaseException(ErrorCode.OTP_DOES_NOT_EXIST));
        List<Token> extoken = tokenRepository.findByUser(user);
        for (Token token : extoken) {
            token.setRevoked(true);
        }
        tokenRepository.saveAll(extoken);

        String newAccessToken = securityUtil.createAccessToken(user);
        String newRefreshToken = securityUtil.createRefreshToken(user);
        Token token = Token.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .user(user)
                .build();
        tokenRepository.save(token);
        // Expired OTP
        if (geotp.getOtp().equals(otp) && Duration.between(
                geotp.getOtpGeneratedTime(),
                LocalDateTime.now()).getSeconds() < (1 * 200)) {
            otpRepository.save(geotp);
            return TokenDto.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .build();
        }
        throw new BaseException(ErrorCode.OTP_EXPIRED);
    }
    @Override
    public String setPassword(SetPasswordRequest setPasswordRequest) {
        String password = setPasswordRequest.getNewPassword();
        String confirmPassword = setPasswordRequest.getConfirmNewPassword();
        if (!password.equals(confirmPassword)) {
            throw new BaseException(ErrorCode.FAILED);
        }
        User user = findByEmail(setPasswordRequest.getEmail());
        String hashedPassword = passwordEncoder.encode(password);
        user.setPassword(hashedPassword);
        userRepository.save(user);
        return SuccessMessage.SUCCESS.getMessage();
    }
    public boolean isEmailExist(String email) {
        return this.userRepository.existsByEmail(email);
    }
    public User findByEmail(String email){
        return userRepository.findByEmail(email).orElseThrow(() -> new BaseException(ErrorCode.USER_DOES_NOT_EXIST));
    }
}
