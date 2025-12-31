package com.splitease

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.splitease.data.repository.AuthRepository
import com.splitease.ui.navigation.Screen
import com.splitease.ui.navigation.SplitEaseNavGraph
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository
) : ViewModel() {
    val currentUser = authRepository.getCurrentUser()
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val currentUser by viewModel.currentUser.collectAsState(initial = null)
            
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // State-driven navigation: if user exists, start at Dashboard, else Login
                    val startDestination = if (currentUser != null) {
                        Screen.Dashboard.route
                    } else {
                        Screen.Login.route
                    }
                    
                    SplitEaseNavGraph(startDestination = startDestination)
                }
            }
        }
    }
}
