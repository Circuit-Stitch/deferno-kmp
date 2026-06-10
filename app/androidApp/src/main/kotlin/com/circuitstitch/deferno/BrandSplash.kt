package com.circuitstitch.deferno

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Brand launch splash drawn in-app (so it carries no launcher/circle mask, unlike the system splash):
 * the Deferno flame centered on the paper-2 background, with the Circuit Stitch company mark small at
 * the bottom centre. It continues the (icon-less) cold-start system splash seamlessly — same
 * background — and [MainActivity] fades it out into the app shell after a brief hold.
 *
 * Theme-independent on purpose: this is fixed brand identity shown before the Account's theme is known.
 */
@Composable
fun BrandSplash(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(250.dp),
        )
        Image(
            painter = painterResource(R.drawable.ic_circuit_stitch),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .width(80.dp),
        )
    }
}
