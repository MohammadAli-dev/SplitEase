package com.splitease.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.splitease.data.local.entities.Person

/**
 * A universal picker for selecting [Person] entities.
 *
 * Supports:
 * - Searching/Filtering by name
 * - Visual indication of selection
 * - explicit "Create Person" action (if [onCreatePerson] is provided)
 */
@Composable
fun PersonPicker(
    persons: List<Person>,
    selectedPersonIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreatePerson: ((name: String, email: String?, phone: String?) -> Unit)? = null,
    allowMultiple: Boolean = true
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredPersons by remember(persons, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                persons
            } else {
                val query = searchQuery.trim().lowercase()
                persons.filter { it.displayName.lowercase().contains(query) }
            }
        }
    }

    if (showAddDialog && onCreatePerson != null) {
        AddPersonDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, email, phone ->
                onCreatePerson(name, email, phone)
                showAddDialog = false
                // Note: We don't wait for success here, the parent should update 'persons' list
            }
        )
    }

    Column(modifier = modifier) {
        // Search Bar & Add Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search people...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            if (onCreatePerson != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add new person",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        LazyColumn {
            items(
                items = filteredPersons,
                key = { it.id }
            ) { person ->
                val isSelected = selectedPersonIds.contains(person.id)
                PersonItem(
                    person = person,
                    isSelected = isSelected,
                    showCheckbox = allowMultiple,
                    onClick = { onToggleSelection(person.id) }
                )
                HorizontalDivider()
            }

            if (filteredPersons.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No matches found for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onCreatePerson != null) {
                            Text(
                                text = "Tap + to create a new person",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonItem(
    person: Person,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(person.displayName) },
        supportingContent = if (person.linkedUserId != null) {
            { Text("Linked Account", style = MaterialTheme.typography.labelSmall) }
        } else null,
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (showCheckbox) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}
