package com.dev.dungcony.modules.users.dtos.res;

import com.dev.dungcony.modules.users.dtos.AddressDto;

public record ReceiverRes(
        int id,
        AddressDto addr,
        String fName,
        String lName,
        String phone) {
}
