package com.example.demo.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionDto {
    private String id;
    private String name;
    private String method;
    private String module;
    private String apiPath;
    private boolean isDelete;
    private String roleName;
}
