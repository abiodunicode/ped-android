package com.ped.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.ped.mobile.R

/** Original supplied artwork, fitted without cropping or recoloring. */
@Composable
internal fun PedLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ped_logo),
        contentDescription = "CIMS · Claims & Inspection Management System",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
