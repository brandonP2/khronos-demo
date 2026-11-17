package com.moga.khronosdemo.services.impl;

import com.moga.khronosdemo.dtos.request.ChangePasswordRequestDTO;
import com.moga.khronosdemo.dtos.request.PasswordResetRequestDTO;
import com.moga.khronosdemo.dtos.request.UserRequestDTO;
import com.moga.khronosdemo.dtos.request.UserUpdateRequestDTO;
import com.moga.khronosdemo.dtos.response.UserResponseDTO;
import com.moga.khronosdemo.entities.enums.Role;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.exceptions.ResourceNotFoundException;
import com.moga.khronosdemo.mappers.UserMapper;
import com.moga.khronosdemo.repositories.EstablishmentRepository;
import com.moga.khronosdemo.repositories.UserRepository;
import com.moga.khronosdemo.repositories.WorkerRepository;
import com.moga.khronosdemo.services.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final EstablishmentRepository establishmentRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, WorkerRepository workerRepository,
                           EstablishmentRepository establishmentRepository, UserMapper userMapper,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.establishmentRepository = establishmentRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserResponseDTO createUser(UserRequestDTO request, Role role, Long creatorId) {
        return createWithRole(request, role, creatorId);
    }

    @Override
    public UserResponseDTO createWorker(UserRequestDTO request, Long creatorId) {
        return createWithRole(request, Role.WORKER, creatorId);
    }

    private UserResponseDTO createWithRole(UserRequestDTO request, Role role, Long creatorId) {

        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessRuleException("Username already in use");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("Email already in use");
        }

        if (request.phoneNumber() != null && userRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new BusinessRuleException("Phone number already in use");
        }

        User user = userMapper.toEntity(request, role);
        if (creatorId != null) {
            user.setCreatedBy(userRepository.getReferenceById(creatorId));
        }

        return userMapper.toResponse(userRepository.save(user));

    }

    @Override
    @Transactional(readOnly = true)
    public UserResponseDTO findById(Long id) {
        return userMapper.toResponse(getUser(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDTO> findAll() {
        return userRepository.findAll().stream().map(userMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDTO> findByEstablishment(Long establishmentId) {
        return userRepository.findWorkersOfEstablishment(establishmentId).stream().map(userMapper::toResponse).toList();
    }

    @Override
    public UserResponseDTO update(Long id, UserUpdateRequestDTO request) {
        User user = getUser(id);
        if (userRepository.existsByUsernameAndIdNot(request.username(), id)) {
            throw new BusinessRuleException("Username already in use");
        }
        if (userRepository.existsByEmailAndIdNot(request.email(), id)) {
            throw new BusinessRuleException("Email already in use");
        }
        if (request.phoneNumber() != null && userRepository.existsByPhoneNumberAndIdNot(request.phoneNumber(), id)) {
            throw new BusinessRuleException("Phone number already in use");
        }
        userMapper.updateEntity(user, request);
        return userMapper.toResponse(user);
    }

    @Override
    public void changePassword(Long id, ChangePasswordRequestDTO request) {

        User user = getUser(id);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessRuleException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setCredentialsChangedAt(Instant.now());

    }

    // Set by an admin or the worker's owner, so there is no current password to check. As with
    // changePassword, every token issued before the reset stops working.
    @Override
    public void resetPassword(Long id, PasswordResetRequestDTO request) {
        User user = getUser(id);
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setCredentialsChangedAt(Instant.now());
    }

    @Override
    public void delete(Long id) {

        User user = getUser(id);
        if (user.getRole() == Role.PLATFORM_ADMIN) {
            throw new BusinessRuleException("Platform admins cannot be deleted through the API");
        }

        if (user.getRole() == Role.OWNER) {
            establishmentRepository.deleteAll(establishmentRepository.findByOwner_Id(id));
        }

        workerRepository.findById(id).ifPresentOrElse(
                workerRepository::delete,
                () -> userRepository.delete(user)
        );

    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
